{
  description = "Hacker's Keyboard development environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f (import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
        # The NDK wrapper puts jdk_headless (JDK 21) on its PATH; reuse the
        # JDK 17 the build needs anyway instead of pulling in a second JDK.
        overlays = [ (final: prev: { jdk_headless = prev.jdk17; }) ];
      }));
    in
    {
      devShells = forAllSystems (pkgs:
        let
          # Keep in sync with app/build.gradle (compileSdkVersion, ndkVersion)
          # and the build-tools version the Android Gradle plugin defaults to.
          buildToolsVersion = "34.0.0";
          android = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "33" "34" ];
            buildToolsVersions = [ buildToolsVersion ];
            includeNDK = true;
            ndkVersions = [ "27.1.12297006" ];
            cmakeVersions = [ "3.22.1" ];
          };
          sdk = "${android.androidsdk}/libexec/android-sdk";
        in
        {
          default = pkgs.mkShell {
            packages = [ pkgs.jdk17 (pkgs.gradle_8.override { java = pkgs.jdk17; }) ];
            ANDROID_HOME = sdk;
            ANDROID_SDK_ROOT = sdk;
            # The aapt2 that Gradle downloads from Maven is dynamically linked
            # and does not run on NixOS; use the one from the SDK instead.
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/${buildToolsVersion}/aapt2";
          };
        });
    };
}
