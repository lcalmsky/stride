cask "stride" do
  version "1.16.0"
  sha256 "4a96bca99200c5568fbb9a3b9c20c7806de87aff4dbd7f65c1a5bd6892f80fb1"

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
