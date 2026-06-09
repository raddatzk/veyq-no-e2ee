#!/usr/bin/env ruby
# frozen_string_literal: true

require "base64"
require "json"
require "net/http"
require "openssl"
require "time"
require "uri"

API_BASE = "https://api.appstoreconnect.apple.com/v1"

def required_env(name)
  value = ENV[name].to_s.strip
  abort("Missing required environment variable: #{name}") if value.empty?
  value
end

def jwt
  key_id = required_env("ASC_KEY_ID")
  issuer_id = required_env("ASC_ISSUER_ID")
  key_path = required_env("ASC_KEY_PATH")
  private_key = OpenSSL::PKey.read(File.read(key_path))
  now = Time.now.to_i
  header = { alg: "ES256", kid: key_id, typ: "JWT" }
  payload = {
    iss: issuer_id,
    iat: now,
    exp: now + 20 * 60,
    aud: "appstoreconnect-v1"
  }
  signing_input = [header, payload].map { |part| b64url(JSON.generate(part)) }.join(".")
  "#{signing_input}.#{ecdsa_signature(private_key, signing_input)}"
end

def b64url(value)
  Base64.urlsafe_encode64(value, padding: false)
end

def ecdsa_signature(private_key, signing_input)
  der_signature = private_key.sign(OpenSSL::Digest::SHA256.new, signing_input)
  sequence = OpenSSL::ASN1.decode(der_signature)
  raw = sequence.value.map do |integer|
    bytes = integer.value.to_s(2)
    bytes = bytes.byteslice(-32, 32) if bytes.bytesize > 32
    bytes.rjust(32, "\0")
  end.join
  b64url(raw)
end

class AppStoreConnect
  def initialize(token)
    @token = token
  end

  def get(path, params = {})
    request(:get, path, params: params)
  end

  def post(path, payload)
    request(:post, path, body: payload)
  end

  def patch(path, payload)
    request(:patch, path, body: payload)
  end

  private

  def request(method, path, params: {}, body: nil)
    uri = URI("#{API_BASE}#{path}")
    uri.query = URI.encode_www_form(params) unless params.empty?
    http = Net::HTTP.new(uri.host, uri.port)
    http.use_ssl = true
    request = request_class(method).new(uri)
    request["Authorization"] = "Bearer #{@token}"
    request["Content-Type"] = "application/json"
    request.body = JSON.generate(body) if body
    response = http.request(request)
    parsed = response.body.to_s.empty? ? {} : JSON.parse(response.body)
    return parsed if response.is_a?(Net::HTTPSuccess)

    details = parsed.dig("errors", 0, "detail") || parsed.dig("errors", 0, "title") || response.body
    raise "App Store Connect #{method.upcase} #{path} failed with HTTP #{response.code}: #{details}"
  end

  def request_class(method)
    {
      get: Net::HTTP::Get,
      post: Net::HTTP::Post,
      patch: Net::HTTP::Patch
    }.fetch(method)
  end
end

def find_group(client, app_id, group_name)
  response = client.get("/apps/#{app_id}/betaGroups", limit: 200)
  response.fetch("data").find { |group| group.dig("attributes", "name") == group_name } ||
    abort("Could not find TestFlight beta group named #{group_name.inspect}.")
end

def beta_group(client, app_id, group_id, group_name)
  return client.get("/betaGroups/#{group_id}").fetch("data") unless group_id.empty?

  abort("Missing required environment variable: TESTFLIGHT_PUBLIC_GROUP_NAME") if group_name.empty?

  find_group(client, app_id, group_name)
end

def find_processed_build(client, app_id, build_number)
  60.times do |attempt|
    response = client.get(
      "/builds",
      "filter[app]" => app_id,
      "filter[version]" => build_number,
      "sort" => "-uploadedDate",
      "limit" => 10
    )
    build = response.fetch("data").first
    state = build&.dig("attributes", "processingState")
    return build if build && state == "VALID"

    abort("Build #{build_number} processing failed with state #{state}.") if %w[FAILED INVALID].include?(state)

    puts "Waiting for App Store Connect processing for build #{build_number} (attempt #{attempt + 1}/60, state #{state || "not found"})."
    sleep 30
  end
  abort("Timed out waiting for App Store Connect processing for build #{build_number}.")
end

def upsert_what_to_test(client, build_id, text)
  response = client.get("/builds/#{build_id}/betaBuildLocalizations", limit: 200)
  localization = response.fetch("data").find { |item| item.dig("attributes", "locale") == "en-US" }
  if localization
    client.patch(
      "/betaBuildLocalizations/#{localization.fetch("id")}",
      data: {
        type: "betaBuildLocalizations",
        id: localization.fetch("id"),
        attributes: { whatsNew: text }
      }
    )
  else
    client.post(
      "/betaBuildLocalizations",
      data: {
        type: "betaBuildLocalizations",
        attributes: {
          locale: "en-US",
          whatsNew: text
        },
        relationships: {
          build: {
            data: {
              type: "builds",
              id: build_id
            }
          }
        }
      }
    )
  end
end

def add_build_to_group(client, group_id, build_id)
  client.post(
    "/betaGroups/#{group_id}/relationships/builds",
    data: [
      {
        type: "builds",
        id: build_id
      }
    ]
  )
rescue RuntimeError => e
  raise unless e.message.include?("HTTP 409")

  puts "Build #{build_id} is already associated with beta group #{group_id}."
end

def submit_beta_review(client, build_id)
  client.post(
    "/betaAppReviewSubmissions",
    data: {
      type: "betaAppReviewSubmissions",
      relationships: {
        build: {
          data: {
            type: "builds",
            id: build_id
          }
        }
      }
    }
  )
rescue RuntimeError => e
  warn "Could not submit build #{build_id} for Beta App Review."
  warn "If another build of this version is already in review, remove or resolve it in App Store Connect, then rerun the workflow."
  raise e
end

app_id = required_env("APP_STORE_CONNECT_APP_ID")
group_id = ENV.fetch("TESTFLIGHT_PUBLIC_GROUP_ID", "").strip
group_name = ENV.fetch("TESTFLIGHT_PUBLIC_GROUP_NAME", "").strip
build_number = required_env("CURRENT_PROJECT_VERSION")
what_to_test = ENV.fetch(
  "TESTFLIGHT_WHAT_TO_TEST",
  "Please test connecting to a veyq instance, browser-based login handoff, tenant selection, Memory navigation, Memory detail loading, media gallery viewing, photo/video uploads through the upload queue, retry behavior for failed uploads and logout."
)

client = AppStoreConnect.new(jwt)
group = beta_group(client, app_id, group_id, group_name)
build = find_processed_build(client, app_id, build_number)
build_id = build.fetch("id")
group_label = group.dig("attributes", "name") || (group_id.empty? ? group_name : group_id)

puts "Distributing build #{build_number} (#{build_id}) to TestFlight group #{group_label.inspect}."
upsert_what_to_test(client, build_id, what_to_test)
add_build_to_group(client, group.fetch("id"), build_id)
submit_beta_review(client, build_id)
puts "Submitted build #{build_number} for TestFlight Beta App Review."
