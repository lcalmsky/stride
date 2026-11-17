cask "stride" do
  version "1.7.0"
  sha256 "d26a1c2d8b575fa11cc3012fe71260c0c3a4cbf71677463a411fddaedd37e66b"

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
