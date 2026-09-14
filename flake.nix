{
  description = "meno — auto-researcher instance (zeno config: publisher + researcher bots)";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "aarch64-darwin" "x86_64-darwin" "aarch64-linux" "x86_64-linux" ];
      forAll = f: nixpkgs.lib.genAttrs systems (s: f nixpkgs.legacyPackages.${s});
    in {
      devShells = forAll (pkgs: {
        default = pkgs.mkShell {
          packages = [ pkgs.clojure pkgs.jdk pkgs.git pkgs.secretspec ];
        };
      });

      # `nix run ~/.zeno` — bundles secretspec (no PATH install needed), loads this
      # instance's secrets, then launches the live zeno runtime against ~/.zeno.
      # Owner creds (ZULIP_OWNER_*) must be declared in secretspec.toml to provision.
      apps = forAll (pkgs: {
        default = {
          type = "app";
          program = toString (pkgs.writeShellScript "meno" ''
            cd ${self}
            exec ${pkgs.secretspec}/bin/secretspec run --reason meno -- \
              nix run github:reflection-dev/zeno -- "$@"
          '');
        };
      });
    };
}
