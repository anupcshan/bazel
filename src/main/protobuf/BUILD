package(default_visibility = ["//visibility:public"])

load("//tools/build_rules:genproto.bzl", "java_proto_library", "cc_grpc_library")
load("//third_party/protobuf:protobuf.bzl", "py_proto_library")

FILES = [
    "build",
    "dash",
    "deps",
    "java_compilation",
    "crosstool_config",
    "extra_actions_base",
    "android_studio_ide_info",
    "package_manifest",
    "test_status",
    "plmerge",
    "bundlemerge",
    "xcodegen",
    "worker_protocol",
    "invocation_policy",
    "remote_protocol",
    "android_deploy_info",
    "apk_manifest",
]

[java_proto_library(
    name = s + "_java_proto",
    src = s + ".proto",
) for s in FILES]

java_proto_library(
    name = "command_server_java_proto",
    src = "command_server.proto",
    use_grpc_plugin = True,
)

cc_grpc_library(
    name = "command_server_cc_proto",
    src = "command_server.proto",
)

py_proto_library(
    name = "build_pb_py",
    srcs = ["build.proto"],
    default_runtime = "//third_party/protobuf:protobuf_python",
    protoc = "//third_party/protobuf:protoc",
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
)

genrule(
    name = "remote_protocol_go_proto",
    srcs = [
        "remote_protocol.proto",
        "//src/build-worker/vendor/github.com/golang/protobuf/protoc-gen-go:protoc-gen-go",
        "//third_party/protobuf:protoc",
    ],
    outs = ["remote_protocol.pb.go"],
    cmd = "set -eu; oldpwd=$$(pwd); \
           protoc_gen_go=$$(echo $(locations //src/build-worker/vendor/github.com/golang/protobuf/protoc-gen-go:protoc-gen-go) | grep -wo '[^\ ]*protoc-gen-go' | uniq); \
           cd $$(dirname $(location :remote_protocol.proto)); \
           $$oldpwd/$(location //third_party/protobuf:protoc) --plugin=$$oldpwd/$$protoc_gen_go --go_out=$$oldpwd/$(@D) $$(basename $(location :remote_protocol.proto))",
)

load("@io_bazel_rules_go//go:def.bzl", "go_library", "go_prefix")

go_library(
    name = "go_default_library",
    srcs = [":remote_protocol_go_proto"],
    deps = [
        "//src/build-worker/vendor/github.com/golang/protobuf/proto:go_default_library",
    ],
)
