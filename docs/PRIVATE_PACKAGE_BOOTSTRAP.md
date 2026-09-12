# Private artifact package bootstrap

The owner has superseded the public GitHub Release artifact design. Do not
dispatch the existing application release workflow or create an application
release tag until its publisher and cross-job artifact transfer have been
migrated to private storage. The Home Node mirror adapter also still needs
that migration. A bootstrap package is not an installable application release.

`package-bootstrap.yml` creates `ghcr.io/hawkeynl/jarvis-client-artifacts`
using only a scratch image with descriptive labels, without source, application
binaries or signing secrets. New GHCR packages are private by default. The job
refuses an existing non-private package and checks private visibility after
upload. It never changes package visibility or repository settings.

Bootstrap runs only from reviewed main, on workflow changes or manual dispatch.
The old public release publisher is explicitly disabled until private artifact
distribution is implemented. The `application-release` environment may require
owner approval. Do not bypass those protections. No signing secret is referenced
by this workflow. Its temporary
GITHUB_TOKEN needs only contents:read and packages:write.

After success, open the GitHub profile's Packages tab and select
`jarvis-client-artifacts`. Verify Private visibility and Actions access for
PersonalJarvisApp. No DNS or SSH credential is needed. The future Home Node
reader will use a separately stored read:packages credential; do not put it in
frontend configuration or source control.

Normal CI still builds/tests Android but no longer uploads its debug APK to
public repository Actions artifacts. Existing historical artifacts have not
been deleted by this change.
