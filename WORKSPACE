workspace(name = "io_bazel")

load("/tools/build_defs/jsonnet/jsonnet", "jsonnet_repositories")
load("/tools/build_rules/rust/rust", "rust_repositories")

jsonnet_repositories()
rust_repositories()

# Protobuf expects an //external:python_headers label which would contain the
# Python headers if fast Python protos is enabled. Since we are not using fast
# Python protos, bind python_headers to a dummy target.
bind(
    name = "python_headers",
    actual = "//:dummy",
)

# Bind to dummy targets if no android SDK/NDK is present.
bind(
    name = "android_sdk_for_testing",
    actual = "//:dummy",
)

bind(
    name = "android_ndk_for_testing",
    actual = "//:dummy",
)

# For tools/cpp/test/...
load("//tools/cpp/test:docker_repository.bzl", "docker_repository")
docker_repository()

# In order to run the Android integration tests, run
# scripts/workspace_user.sh and uncomment the next two lines.
# load("/WORKSPACE.user", "android_repositories")
# android_repositories()

# This allows rules written in skylark to locate apple build tools.
bind(name = "xcrunwrapper", actual = "@bazel_tools//tools/objc:xcrunwrapper")

git_repository(
    name = "io_bazel_rules_go",
    remote = "https://github.com/bazelbuild/rules_go.git",
    commit = "373feb67001252371054c3388291661352c4eb90",  # Tag 0.0.1 doesn't set go_prefix correctly
)

load("@io_bazel_rules_go//go:def.bzl", "go_repositories")

go_repositories()
