cask "stride" do
  version "1.6.0"
  sha256 "657e1cc7432064a9446703694a029ce013231db4a0be1196f491706ac211d387"

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
