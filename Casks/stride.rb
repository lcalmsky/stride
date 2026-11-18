cask "stride" do
  version "1.15.0"
  sha256 "e5732365e11ef51e1ee83bde23ee34dbc0ca9108f077cb4626ba979d83a8625d"

  url "https://github.com/lcalmsky/stride/releases/download/v#{version}/strIDE-#{version}-arm64.zip"
  name "strIDE"
  desc "Keyboard-driven code editor"
  homepage "https://github.com/lcalmsky/stride"

  depends_on arch: :arm64

  app "strIDE.app"

  # The bundle is ad-hoc signed, so drop the quarantine flag that would block the first launch.
  postflight do
    system_command "/usr/bin/xattr",
                   args: ["-dr", "com.apple.quarantine", "#{appdir}/strIDE.app"]
  end
end
