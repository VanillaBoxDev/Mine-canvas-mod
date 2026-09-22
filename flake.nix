{
  description = "mine-canvas development shell";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";

  outputs = { nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs { inherit system; };
      jdk25 = pkgs.jdk25;
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = [ jdk25 pkgs.git ];

        shellHook = ''
          export JAVA_HOME=${jdk25.home}
          export GRADLE_OPTS="-Dorg.gradle.java.installations.paths=${jdk25.home}"
        '';
      };
    };
}
