# veyq Hosting and Operations Strategy

This document captures exploratory ideas for veyq offerings beyond the self-hosted community baseline. It is not an implementation commitment. The self-hostable Memory product remains the default baseline, and commercial or hosted layers must stay optional as described in [Commercial Boundary](../legal/commercial-boundary.md).

## Product Offerings

### veyq Cloud

veyq Cloud is a possible public SaaS offering hosted by veyq at `veyq.app`.

Product shape:

- No visible tenants.
- No visible groups.
- No custom domains.
- Personal Space for every user.
- Collaborative sharing through invitations.
- Shared Memories appear in the normal Memory overview.
- Users may organize shared Memories in their own folder structure independently.
- Runs on a dedicated veyq Cloud platform or cluster.

Conceptually, a cloud user has:

```text
User
|- Personal Memories
`- Shared Memories
```

Internally, collaborative shares may use hidden groups or spaces as ownership and permission constructs. These should not leak into the Cloud UI.

### Managed veyq Standard

Managed veyq Standard is a dedicated managed instance operated by veyq for one customer.

Product shape:

- Dedicated veyq instance per customer.
- Own database.
- Own storage.
- Optional custom domain.
- Groups enabled.
- User management enabled.

Possible infrastructure:

```text
Azure Resource Group
|- Azure Container Apps
|- PostgreSQL Flexible Server
|- Storage
|- Backup
`- Monitoring
```

### Managed veyq Premium

Managed veyq Premium is a higher-isolation managed offering for compliance-oriented deployments.

Product shape:

- Dedicated infrastructure.
- Own Azure subscription.
- Own AKS cluster.
- Own database and storage.
- Compliance-oriented deployment model.

Possible infrastructure:

```text
Azure Subscription
|- AKS
|- PostgreSQL
|- Storage
|- Monitoring
`- Backup
```

### Self-Hosted

Self-hosted veyq remains the customer-operated deployment path.

Supported targets may include:

- Docker Compose.
- Kubernetes.
- Other supported environments.

## Collaboration Model

### Cloud

Cloud should hide tenant and group concepts. Users collaborate directly on Memories.

Example:

```text
Memory
|- Owner
|- Editor
|- Contributor
`- Viewer
```

Invitations are sent by email. Shared Memories appear in the normal Memory overview. Each user may independently organize shared Memories into their own folders.

A hidden group may exist internally:

```text
Hidden Group
`- Shared Memory
```

The hidden group is only an ownership and permission implementation detail.

### Managed and Self-Hosted

Managed and self-hosted deployments keep the current tenant, personal space and group model visible.

```text
Tenant
|- Personal Spaces
`- Groups
```

Shared Memories typically live inside groups.

## Billing Strategy

### Cloud

Cloud pricing should not expose infrastructure-based billing to end users.

Users should not see:

- CPU.
- RAM.
- AKS costs.
- Request counts.
- Traffic costs.

Cloud pricing should primarily use:

- Storage tiers.
- Feature tiers.

Example:

```text
Free
Plus
Family
Pro
```

Optional storage add-ons may be offered. Platform costs such as AKS, monitoring, logging, ingress and control plane operation are shared platform costs and should be priced into subscription tiers. Storage, backup and traffic should be monitored internally for capacity planning, not directly billed to end users.

### Managed Standard

Managed Standard can use fixed plan pricing.

Example:

```text
Plan + Included Storage + Hosting & Operations
```

### Managed Premium

Managed Premium can use infrastructure-based pricing.

Example:

```text
Azure Cost + Management Fee
```

## Operations

### Provisioning

Provisioning should use infrastructure as code.

Candidate technologies:

- Terraform.
- Helm.

An internal operations repository may contain:

```text
terraform/
helm/
environments/
runbooks/
```

Terraform may provision:

- Resource groups.
- Subscriptions.
- AKS.
- Container Apps.
- PostgreSQL.
- Storage.
- Monitoring.
- DNS.

Helm may deploy veyq workloads.

### Control Plane

A future veyq Control Plane may manage:

- Customers.
- Subscriptions.
- Plans.
- Domains.
- Deployments.
- Versions.
- Backups.
- Monitoring status.
- Billing information.

### Deployments

Managed release flow:

```text
Release -> Build images -> Publish artifacts -> Trigger deployments
```

Managed instances should support:

- Staged rollouts.
- Rollbacks.
- Version pinning.
- Maintenance windows.

### Backups

Backups should be configurable per instance.

Potential options:

```text
Disabled
Standard
Extended Retention
Premium
```

Backup and restore workflows should be manageable per instance.

## Long-Term Privacy and E2EE Strategy

Personal content should not require trusting the veyq operator.

Long-term objective:

```text
Operator cannot access personal media contents.
```

### Personal Space

Personal Space should be treated as the primary E2EE domain.

Future vision:

```text
Client -> Encrypt -> Upload -> Store
```

The server stores encrypted data.

### Shared Memories

Shared Memories may support multiple privacy modes.

Example:

```text
Strict E2EE
Feature-Rich Processing
```

Participants decide which mode is used.

## Media Processing Architecture

Media processing should be designed as separate services from the beginning.

Processing examples:

- Thumbnail generation.
- Previews.
- Transcoding.
- OCR.
- Face recognition.
- AI enrichment.
- Embeddings.
- Indexing.

Future execution targets:

```text
veyq Server
Customer-Controlled Worker
Client Device
```

The logical processing pipeline should remain consistent regardless of execution location.

### Worker Architecture

A future generic processing worker may be named:

```text
veyq-worker
```

Possible deployment locations:

```text
Server-side
Customer-controlled node
Client device
```

This allows privacy guarantees to evolve without redesigning the media pipeline.

## Design Principle

Do not tightly couple media processing to the server trust boundary.

The architecture should remain compatible with future full E2EE deployments while keeping self-hosting useful without central veyq infrastructure.
