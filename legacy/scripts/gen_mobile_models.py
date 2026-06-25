#!/usr/bin/env python3
"""Spike: generate Swift + Kotlin API *models* from the same docs/api OpenAPI specs that drive the
backend interfaces — so the DTO shape has one source of truth instead of three hand-written copies.

Only models are generated; the hand-written transport + crypto layer (URLSession / java.net.http +
AEAD wrap-chain) stays. The domain specs reference shared schemas via external file refs
(`./common.openapi.yaml#/components/...`); swagger-parser would inline those and lose the names
(`Blob` -> `CreateGroupRequestWrappedScopePrivateKey`), so — exactly like backend/gradle/openapi.gradle.kts
— we first bundle every spec into ONE doc with the common components inlined under their real names, then
generate the model set once per language.

Usage:  python3 scripts/gen_mobile_models.py [--swift] [--kotlin]   (default: both)
Requires: npx (Node) — pulls @openapitools/openapi-generator-cli on first run; Java 17+; PyYAML.
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
SPEC_DIR = ROOT / "docs" / "api"

# common.openapi.yaml has no paths (shared components only); it is pulled in via $ref from every spec.
COMMON = SPEC_DIR / "common.openapi.yaml"
SPECS = sorted(p for p in SPEC_DIR.glob("*.openapi.yaml") if p.name != "common.openapi.yaml")
COMPONENT_KINDS = ("schemas", "responses", "parameters", "requestBodies", "headers")


def rewrite_refs(node):
    """Rewrite external common-file refs to local refs so one bundled doc resolves in-place."""
    if isinstance(node, dict):
        return {k: ("#" + v.split("#", 1)[1] if k == "$ref" and isinstance(v, str) and "common.openapi.yaml#" in v
                    else rewrite_refs(v)) for k, v in node.items()}
    if isinstance(node, list):
        return [rewrite_refs(x) for x in node]
    return node


def strip_server_annotations(node):
    """Drop backend-only vendor extensions (x-*-extra-annotation) — e.g. `x-field-extra-annotation` carries
    Quarkus/jakarta Bean-Validation annotations the jaxrs-spec backend emits. The Kotlin generator honours
    them and would inject `@field:jakarta.validation…` into the mobile models, which don't depend on jakarta.
    The clients validate nothing server-side, so these are noise on mobile."""
    if isinstance(node, dict):
        return {k: strip_server_annotations(v) for k, v in node.items() if not (isinstance(k, str) and k.endswith("-extra-annotation"))}
    if isinstance(node, list):
        return [strip_server_annotations(x) for x in node]
    return node


def bundle_specs() -> dict:
    """Merge all domain specs + common components into a single OpenAPI doc (names preserved)."""
    common = yaml.safe_load(COMMON.read_text())
    merged = {
        "openapi": "3.1.0",
        "info": {"title": "veyq API (bundled)", "version": "0.0.0"},
        "paths": {},
        "components": {kind: {} for kind in COMPONENT_KINDS},
    }
    # seed shared components first
    for kind in COMPONENT_KINDS:
        for name, comp in (common.get("components", {}).get(kind, {}) or {}).items():
            merged["components"][kind].setdefault(name, rewrite_refs(comp))
    # then each domain spec's paths + its own components
    for spec_path in SPECS:
        spec = rewrite_refs(yaml.safe_load(spec_path.read_text()))
        merged["paths"].update(spec.get("paths", {}) or {})
        for kind in COMPONENT_KINDS:
            for name, comp in (spec.get("components", {}).get(kind, {}) or {}).items():
                merged["components"][kind].setdefault(name, comp)
    return strip_server_annotations(merged)

SWIFT_OUT = ROOT / "apple" / "Sources" / "VeyqApiModels"
KOTLIN_PKG = "app.veyq.apimodels"
KOTLIN_OUT = ROOT / "android" / "core" / "src" / "generated" / "kotlin" / "app" / "veyq" / "apimodels"

LOCAL_GENERATOR = ROOT / "frontend" / "node_modules" / ".bin" / "openapi-generator-cli"
LOCAL_GENERATOR_JAR = ROOT / "frontend" / "node_modules" / "@openapitools" / "openapi-generator-cli" / "versions" / "7.21.0.jar"
GENERATOR = ["java", "-jar", str(LOCAL_GENERATOR_JAR), "generate"] if LOCAL_GENERATOR_JAR.exists() else ["npx", "--yes", "@openapitools/openapi-generator-cli", "generate"]
GENERATOR_CWD = ROOT

HEADER = "// GENERATED from docs/api/*.openapi.yaml by scripts/gen_mobile_models.py — DO NOT EDIT.\n"


def run_generator(spec: Path, gen: str, out: Path, extra: list[str]) -> None:
    cmd = GENERATOR + [
        "-g", gen,
        "-i", str(spec),
        "-o", str(out),
        "--global-property", "models,modelTests=false,modelDocs=false",  # model classes only
        "--skip-validate-spec",
    ] + extra
    res = subprocess.run(cmd, cwd=GENERATOR_CWD, capture_output=True, text=True)
    if res.returncode != 0:
        sys.stderr.write(f"\n[gen {gen}] FAILED\n{res.stdout[-3000:]}\n{res.stderr[-2000:]}\n")
        raise SystemExit(1)


def swift_sanitize(text: str) -> str:
    """Drop the swift5 generator's `*Rule = ArrayRule(...)` validation statics — they reference an
    Infrastructure `Validatable` type we deliberately don't ship (models-only). No `validate()` is
    emitted that uses them, so removing the lines is safe."""
    rules = ("= ArrayRule", "= StringRule", "= NumericRule")  # may carry a generic arg, e.g. NumericRule<Int>(
    return "".join(line for line in text.splitlines(keepends=True) if not any(r in line for r in rules))


def collect(tmpdir: Path, pattern: str, out: Path, transform=None) -> int:
    out.mkdir(parents=True)
    n = 0
    for f in sorted(tmpdir.glob(pattern)):
        if f.name.endswith("Test.kt"):
            continue
        text = f.read_text()
        if transform:
            text = transform(text)
        (out / f.name).write_text(HEADER + text)
        n += 1
    return n


def gen_swift(bundled: Path) -> None:
    print("==> Swift models")
    if SWIFT_OUT.exists():
        shutil.rmtree(SWIFT_OUT)
    with tempfile.TemporaryDirectory() as tmp:
        tmpdir = Path(tmp)
        # useJsonEncodable=false keeps models pure `Codable` (Foundation only) — no generator infra needed,
        # so the model set drops cleanly next to our hand-written transport layer.
        run_generator(bundled, "swift5", tmpdir,
                      ["--additional-properties", "projectName=VeyqApiModels,readonlyProperties=true,useJsonEncodable=false,hashableModels=false"])
        n = collect(tmpdir, "**/Models/**/*.swift", SWIFT_OUT, transform=swift_sanitize)
        print(f"    {n} model files -> {SWIFT_OUT.relative_to(ROOT)}")


def gen_kotlin(bundled: Path) -> None:
    print("==> Kotlin models")
    if KOTLIN_OUT.exists():
        shutil.rmtree(KOTLIN_OUT)
    with tempfile.TemporaryDirectory() as tmp:
        tmpdir = Path(tmp)
        run_generator(bundled, "kotlin", tmpdir,
                      ["--additional-properties",
                       f"packageName={KOTLIN_PKG},modelPackage={KOTLIN_PKG},"
                       "serializationLibrary=kotlinx_serialization,enumPropertyNaming=original,collectionType=list"])
        n = collect(tmpdir, f"**/{KOTLIN_PKG.replace('.', '/')}/**/*.kt", KOTLIN_OUT)
        print(f"    {n} model files -> {KOTLIN_OUT.relative_to(ROOT)}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--swift", action="store_true")
    ap.add_argument("--kotlin", action="store_true")
    args = ap.parse_args()
    both = not (args.swift or args.kotlin)
    print(f"bundling {len(SPECS)} specs: {', '.join(s.stem.replace('.openapi','') for s in SPECS)}")
    merged = bundle_specs()
    with tempfile.NamedTemporaryFile("w", suffix=".openapi.yaml", delete=False) as tf:
        yaml.safe_dump(merged, tf, sort_keys=False)
        bundled = Path(tf.name)
    try:
        if args.swift or both:
            gen_swift(bundled)
        if args.kotlin or both:
            gen_kotlin(bundled)
    finally:
        bundled.unlink(missing_ok=True)
    print("done.")


if __name__ == "__main__":
    main()
