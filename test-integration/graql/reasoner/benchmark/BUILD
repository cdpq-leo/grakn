load("@graknlabs_build_tools//checkstyle:rules.bzl", "checkstyle_test")

java_test(
    name = "rule-scaling-it",
    size = "small",
    srcs = ["RuleScalingIT.java"],
    classpath_resources = ["//test-integration/resources:logback-test"],
    test_class = "grakn.core.graql.reasoner.benchmark.RuleScalingIT",
    deps = [
        "//concept",
        "//dependencies/maven/artifacts/com/google/guava",
        "//dependencies/maven/artifacts/org/hamcrest:hamcrest-library",
        "//dependencies/maven/artifacts/org/slf4j:slf4j-api",
        "//server",
        "//test-integration/rule:grakn-test-server",
        "@graknlabs_client_java//:client-java",
        "@graknlabs_graql//java:graql",
    ],
)

java_test(
    name = "benchmark-big-it",
    size = "large",
    srcs = ["BenchmarkBigIT.java"],
    classpath_resources = ["//test-integration/resources:logback-test"],
    data = [
        "//test-integration/graql/reasoner/resources:linear-transivity",
        "//test-integration/graql/reasoner/resources:multi-join",
    ],
    test_class = "grakn.core.graql.reasoner.benchmark.BenchmarkBigIT",
    deps = [
        "//concept",
        "//dependencies/maven/artifacts/com/google/guava",
        "//dependencies/maven/artifacts/org/hamcrest:hamcrest-library",
        "//dependencies/maven/artifacts/org/slf4j:slf4j-api",
        "//server",
        "//test-integration/rule:grakn-test-server",
        "@graknlabs_client_java//:client-java",
        "@graknlabs_graql//java:graql",
    ],
)

java_test(
    name = "benchmark-small-it",
    size = "large",
    srcs = ["BenchmarkSmallIT.java"],
    classpath_resources = ["//test-integration/resources:logback-test"],
    resources = [
        "//test-integration/graql/reasoner/resources:generic-schema",
        "//test-integration/graql/reasoner/resources:unification-with-types",
    ],
    test_class = "grakn.core.graql.reasoner.benchmark.BenchmarkSmallIT",
    deps = [
        "//concept",
        "//dependencies/maven/artifacts/com/google/guava",
        "//server",
        "//test-integration/graql/reasoner/graph:diagonal-graph",
        "//test-integration/graql/reasoner/graph:linear-transitivity-matrix-graph",
        "//test-integration/graql/reasoner/graph:path-tree-graph",
        "//test-integration/graql/reasoner/graph:transitivity-chain-graph",
        "//test-integration/graql/reasoner/graph:transitivity-matrix-graph",
        "//test-integration/rule:grakn-test-server",
        "@graknlabs_client_java//:client-java",
        "@graknlabs_graql//java:graql",
    ],
)

checkstyle_test(
    name = "checkstyle",
    targets = [
        ":rule-scaling-it",
        ":benchmark-big-it",
        ":benchmark-small-it",
    ],
)
