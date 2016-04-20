package(default_visibility = ["//visibility:public"])

load("@io_bazel_rules_go//go:def.bzl", "go_library", "go_prefix")

go_library(
    name = "go_default_library",
    srcs = glob(["*.go"]),
    deps = [
        "//remote:go_default_library",
        "//vendor/github.com/golang/protobuf/proto:go_default_library",
    ],
)
