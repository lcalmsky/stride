cask "stride" do
  version "1.11.0"
  sha256 "b6ffc554b9e9128988942a16a28d7e255190f43c3b425be450a4c8018595420e"

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
