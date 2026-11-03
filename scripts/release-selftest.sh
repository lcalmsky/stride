#!/bin/bash
set -euo pipefail

src="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
real_git="$(command -v git)"
work="$(mktemp -d "${TMPDIR:-/tmp}/release-selftest.XXXXXX")"
trap 'rm -rf "$work"' EXIT

repo="$work/repo"
first="$work/first"
shims="$work/shims"
mkdir -p "$repo/scripts" "$repo/app" "$repo/Casks" "$first/scripts" "$first/app" "$first/Casks" "$shims"
cp "$src/scripts/release.sh" "$repo/scripts/release.sh"
cp "$src/scripts/release.sh" "$first/scripts/release.sh"

cat > "$shims/ditto" <<EOF
#!/bin/bash
echo "\$*" >> "$work/ditto.log"
out="\${@: -1}"
mkdir -p "\$(dirname "\$out")"
printf 'fixed-zip' > "\$out"
EOF
cat > "$shims/curl" <<'EOF'
#!/bin/bash
if [[ ${FAKE_CURL_MISMATCH:-} == 1 ]]; then
    printf 'other-zip'
else
    printf 'fixed-zip'
fi
EOF
chmod +x "$shims/ditto" "$shims/curl"

seed_repo() {
    local dir="$1"
    cat > "$dir/app/build.gradle.kts" <<'EOF'
nativeDistributions {
    packageName = "strIDE"
    packageVersion = "1.0.0"
}
EOF
    cat > "$dir/Casks/stride.rb" <<'EOF'
cask "stride" do
  version "1.0.0"
  sha256 "0000000000000000000000000000000000000000000000000000000000000000"

  url "https://github.com/lcalmsky/stride/releases/download/v#{version}/strIDE-#{version}-arm64.zip"
  name "strIDE"
  desc "Keyboard-driven code editor"
  homepage "https://github.com/lcalmsky/stride"

  depends_on arch: :arm64

  app "strIDE.app"

  postflight do
    system_command "/usr/bin/xattr",
                   args: ["-dr", "com.apple.quarantine", "#{appdir}/strIDE.app"]
  end
end
EOF
    printf 'build/\n' > "$dir/.gitignore"
    cat > "$dir/gradlew" <<EOF
#!/bin/bash
echo "\$*" >> "$work/gradlew.log"
if [[ "\$*" == *":app:createDistributable"* ]]; then
    [[ \$FAKE_GRADLE_FAIL_PACKAGE != FAIL ]] || exit 1
    if [[ \$FAKE_GRADLE_NO_BUNDLE != 1 ]]; then
        bin="\$(pwd)/app/build/compose/binaries/main/app/strIDE.app/Contents/MacOS"
        mkdir -p "\$bin"
        printf 'stride' > "\$bin/strIDE"
        chmod +x "\$bin/strIDE"
    fi
else
    [[ \$FAKE_GRADLE_FAIL_TEST != FAIL ]] || exit 1
fi
exit 0
EOF
    chmod +x "$dir/gradlew"
}

seed_repo "$repo"
seed_repo "$first"

export GIT_AUTHOR_NAME=selftest GIT_AUTHOR_EMAIL=selftest@example.com
export GIT_COMMITTER_NAME=selftest GIT_COMMITTER_EMAIL=selftest@example.com
export GIT_CONFIG_COUNT=2 GIT_CONFIG_KEY_0=commit.gpgsign GIT_CONFIG_VALUE_0=false
export GIT_CONFIG_KEY_1=tag.gpgsign GIT_CONFIG_VALUE_1=false

g() { "$real_git" -C "$repo" "$@"; }
gf() { "$real_git" -C "$first" "$@"; }

g init -q -b main
g add -A
g commit -q -m "chore: 시험 저장소"
setup_sha="$(g rev-parse HEAD)"
g tag -a v1.0.0 -m "시험 태그"
g remote add origin git@github-lcalmsky:lcalmsky/stride.git

gf init -q -b main
gf add -A
gf commit -q -m "chore: 시험 저장소"
gf remote add origin git@github-lcalmsky:lcalmsky/stride.git

# release.sh 가 부르는 외부 명령은 네트워크나 빌드가 필요하므로 호출을 기록만 하는 가짜로 바꾼다
: > "$work/push.log"
: > "$work/gh.log"
: > "$work/ditto.log"
: > "$work/gradlew.log"
cat > "$shims/gh" <<EOF
#!/bin/bash
echo "\$*" >> "$work/gh.log"
[[ "\$1 \$2" != "api user" ]] || echo lcalmsky
if [[ "\$1 \$2" == "release create" ]]; then
    while ((\$# > 0)); do
        if [[ "\$1" == --notes-file ]]; then
            cp "\$2" "$work/release-notes"
        fi
        shift
    done
fi
exit 0
EOF
cat > "$shims/git" <<EOF
#!/bin/bash
if [[ "\$*" == *ls-remote* ]]; then
    [[ \$FAKE_LS_REMOTE != FAIL ]] || exit 128
    printf '%s' "\$FAKE_LS_REMOTE"
    exit 0
fi
if [[ "\$1" == push ]]; then
    echo "\$*" >> "$work/push.log"
    exit 0
fi
exec "$real_git" "\$@"
EOF
chmod +x "$shims/gh" "$shims/git"
export PATH="$shims:$PATH"

fixed_sha="$(printf 'fixed-zip' | shasum -a 256 | awk '{print $1}')"

total=0
failures=0
out=""
status=0

run() {
    set +e
    out="$(cd "$repo" && scripts/release.sh "$@" 2>&1)"
    status=$?
    set -e
}

run_first() {
    set +e
    out="$(cd "$first" && scripts/release.sh "$@" 2>&1)"
    status=$?
    set -e
}

check() {
    local name="$1" want_status="$2" want_text="$3"
    total=$((total + 1))
    if ((status != want_status)) || [[ $out != *"$want_text"* ]]; then
        failures=$((failures + 1))
        echo "실패: $name (종료 $status, 기대 $want_status, 기대 문구 \"$want_text\")"
        printf '%s\n' "$out" | sed 's/^/    /'
    fi
}

check_absent() {
    local name="$1" unwanted="$2"
    total=$((total + 1))
    if [[ $out == *"$unwanted"* ]]; then
        failures=$((failures + 1))
        echo "실패: $name (출력에 \"$unwanted\" 가 있으면 안 된다)"
        printf '%s\n' "$out" | sed 's/^/    /'
    fi
}

expect_eq() {
    local name="$1" actual="$2" expected="$3"
    total=$((total + 1))
    if [[ $actual != "$expected" ]]; then
        failures=$((failures + 1))
        echo "실패: $name (실제 \"$actual\", 기대 \"$expected\")"
    fi
}

cask_line() { grep "^  $1 " "$repo/Casks/stride.rb"; }
first_cask_line() { grep "^  $1 " "$first/Casks/stride.rb"; }

export FAKE_LS_REMOTE=$'0000000000000000000000000000000000000000\trefs/tags/v1.0.0\n'

run --cask-only
check "재개: 버전 누락" 1 "버전을 넘기세요"

run --cask-only abc
check "재개: 버전 형식 오류" 1 "X.Y.Z 형식이어야 합니다"

run --cask-only 1.0.0 -m x
check "재개: -m 거부" 1 "받지 않습니다"

run --cask-only 1.0.0 --notes-file x
check "재개: --notes-file 거부" 1 "받지 않습니다"

run --cask-only 9.9.9
check "재개: 로컬 태그 없음" 1 "로컬에 태그 v9.9.9 이 없습니다"

FAKE_LS_REMOTE="" run --cask-only 1.0.0
check "재개: 원격 태그 없음" 1 "원격 origin 에 태그 v1.0.0 이 없습니다"

FAKE_LS_REMOTE=FAIL run --cask-only 1.0.0
check "재개: 원격 조회 실패" 1 "원격 태그를 조회하지 못했습니다"

touch "$repo/dirty"
run --cask-only 1.0.0
check "재개: 더러운 워킹트리" 1 "워킹트리가 깨끗하지 않습니다"
rm "$repo/dirty"

run --cask-only 1.0.0 --dry-run
check "재개 dry-run: 7단계 출력" 0 "7. https://github.com/lcalmsky/stride/releases/download/v1.0.0/strIDE-1.0.0-arm64.zip"
check "재개 dry-run: 9단계 출력" 0 "9. 릴리즈 URL"
check_absent "재개 dry-run: 2단계 생략" "2. app/build.gradle.kts"
check_absent "재개 dry-run: 6단계 생략" "6. app/build"

run 1.0.0 -m x --dry-run
check "전체: 기존 태그 거부" 1 "태그 v1.0.0 이 이미 있습니다"

run 0.9.0 -m x --dry-run
check "전체: 낮은 버전 거부" 1 "현재 버전 1.0.0 보다 크지 않습니다"

run 1.0.1 -m x --dry-run
check "전체 dry-run: 2단계 출력" 0 '2. app/build.gradle.kts 의 packageVersion = "1.0.0"'
check "전체 dry-run: 6단계 출력" 0 "gh release create v1.0.1"
check "전체 dry-run: 9단계 출력" 0 "9. 릴리즈 URL"

run --cask-only 1.0.0
check "재개 실행: 종료" 0 "릴리즈: https://github.com/lcalmsky/stride/releases/tag/v1.0.0"
expect_eq "재개 실행: url 유지" "$(cask_line url)" '  url "https://github.com/lcalmsky/stride/releases/download/v#{version}/strIDE-#{version}-arm64.zip"'
expect_eq "재개 실행: version 유지" "$(cask_line version)" '  version "1.0.0"'
expect_eq "재개 실행: sha256 갱신" "$(cask_line sha256)" "  sha256 \"$fixed_sha\""
expect_eq "재개 실행: Cask 커밋" "$(g log -1 --format=%s)" "chore: Cask v1.0.0"
expect_eq "재개 실행: packageVersion 유지" "$(grep packageVersion "$repo/app/build.gradle.kts")" '    packageVersion = "1.0.0"'
expect_eq "재개 실행: 태그 유지" "$(g tag -l | tr '\n' ' ')" "v1.0.0 "
expect_eq "재개 실행: 푸시" "$(cat "$work/push.log")" "push origin main"
expect_eq "재개 실행: 릴리즈 생성 없음" "$(grep -c 'release create' "$work/gh.log" || true)" "0"

: > "$work/push.log"
FAKE_GRADLE_FAIL_TEST=FAIL run 1.0.1 -m x
check "전체 실행: 테스트 실패" 1 "테스트가 실패해 버전 변경을 되돌렸습니다"
expect_eq "전체 실행: 테스트 실패 후 버전 되돌림" "$(grep packageVersion "$repo/app/build.gradle.kts")" '    packageVersion = "1.0.0"'
expect_eq "전체 실행: 테스트 실패 후 푸시 없음" "$(cat "$work/push.log")" ""

FAKE_GRADLE_FAIL_PACKAGE=FAIL run 1.0.1 -m x
check "전체 실행: 패키징 실패" 1 "패키징이 실패해 버전 변경을 되돌렸습니다"
expect_eq "전체 실행: 패키징 실패 후 버전 되돌림" "$(grep packageVersion "$repo/app/build.gradle.kts")" '    packageVersion = "1.0.0"'

FAKE_GRADLE_NO_BUNDLE=1 run 1.0.1 -m x
check "전체 실행: 번들 없음" 1 "번들이 만들어지지 않았습니다"
expect_eq "전체 실행: 번들 없음 후 버전 되돌림" "$(grep packageVersion "$repo/app/build.gradle.kts")" '    packageVersion = "1.0.0"'
expect_eq "전체 실행: 번들 없음 후 태그 없음" "$(g tag -l 'v1.0.1' | tr '\n' ' ')" ""
expect_eq "전체 실행: 번들 없음 후 푸시 없음" "$(cat "$work/push.log")" ""

before="$(g rev-parse HEAD)"
FAKE_CURL_MISMATCH=1 run 1.0.1 -m x
check "전체 실행: 자산 sha256 불일치" 1 "sha256 이 로컬 zip 과 다릅니다"
g tag -d v1.0.1 >/dev/null
g reset --hard -q "$before"

: > "$work/push.log"
: > "$work/gh.log"
: > "$work/ditto.log"
run 1.0.1 -m x
check "전체 실행: 종료" 0 "릴리즈: https://github.com/lcalmsky/stride/releases/tag/v1.0.1"
expect_eq "전체 실행: 버전 변경" "$(grep packageVersion "$repo/app/build.gradle.kts")" '    packageVersion = "1.0.1"'
expect_eq "전체 실행: 태그 생성" "$(g tag -l | tr '\n' ' ')" "v1.0.0 v1.0.1 "
expect_eq "전체 실행: 버전 커밋" "$(g log -2 --format=%s | tail -1)" "chore: x 버전 1.0.1"
expect_eq "전체 실행: Cask 커밋" "$(g log -1 --format=%s)" "chore: Cask v1.0.1"
expect_eq "전체 실행: Cask version 갱신" "$(cask_line version)" '  version "1.0.1"'
expect_eq "전체 실행: Cask sha256 갱신" "$(cask_line sha256)" "  sha256 \"$fixed_sha\""
expect_eq "전체 실행: Cask 두 줄만 변경" "$(g diff --numstat "$setup_sha" HEAD -- Casks/stride.rb | awk '{print $1 "/" $2}')" "2/2"
expect_eq "전체 실행: Cask 변경 줄" "$(g diff --unified=0 "$setup_sha" HEAD -- Casks/stride.rb | grep -E '^[+-]  ' | sed -E 's/^([+-])  ([a-z0-9]+).*/\1 \2/' | tr '\n' '|')" '- version|- sha256|+ version|+ sha256|'
expect_eq "전체 실행: 푸시 순서" "$(tr '\n' '|' < "$work/push.log")" "push origin main|push origin v1.0.1|push origin main|"
expect_eq "전체 실행: 릴리즈 생성" "$(grep -c '^release create v1.0.1 ' "$work/gh.log")" "1"
expect_eq "전체 실행: zip 자산 첨부" "$(grep -c '^release create v1.0.1 .*build/release/strIDE-1.0.1-arm64.zip$' "$work/gh.log")" "1"
expect_eq "전체 실행: ditto 인자" "$(grep -c 'keepParent app/build/compose/binaries/main/app/strIDE.app build/release/strIDE-1.0.1-arm64.zip' "$work/ditto.log")" "1"
check "전체 실행: 업데이트 안내" 0 "업데이트: brew upgrade --cask lcalmsky/stride/stride"
expect_eq "전체 실행: 릴리즈 노트 설치 안내" "$(grep -c 'brew install --cask lcalmsky/stride/stride' "$work/release-notes" || true)" "1"
expect_eq "전체 실행: 릴리즈 노트 업데이트 안내" "$(grep -c 'brew upgrade --cask lcalmsky/stride/stride' "$work/release-notes" || true)" "1"

run_first 1.0.0 -m "첫 릴리즈" --dry-run
check "첫 릴리즈: 같은 버전 허용" 0 "검사 통과: 1.0.0 -> 1.0.0"
check "첫 릴리즈 dry-run: 2단계 건너뜀" 0 "2. packageVersion 이 이미 1.0.0"
check "첫 릴리즈 dry-run: 4단계 건너뜀" 0 "4. 바뀐 파일이 없어 커밋을 건너뛴다"

run_first 1.0.1 -m x --dry-run
check "첫 릴리즈: 높은 버전 허용" 0 "검사 통과: 1.0.0 -> 1.0.1"

run_first 0.9.0 -m x --dry-run
check "첫 릴리즈: 낮은 버전 거부" 1 "보다 작습니다"

: > "$work/push.log"
run_first 1.0.0 -m "첫 릴리즈"
check "첫 릴리즈 실행: 종료" 0 "릴리즈: https://github.com/lcalmsky/stride/releases/tag/v1.0.0"
expect_eq "첫 릴리즈 실행: 버전 유지" "$(grep packageVersion "$first/app/build.gradle.kts")" '    packageVersion = "1.0.0"'
expect_eq "첫 릴리즈 실행: 버전 커밋 없음" "$(gf log -2 --format=%s | tr '\n' '|')" "chore: Cask v1.0.0|chore: 시험 저장소|"
expect_eq "첫 릴리즈 실행: 태그 생성" "$(gf tag -l | tr '\n' ' ')" "v1.0.0 "
expect_eq "첫 릴리즈 실행: Cask sha256 갱신" "$(first_cask_line sha256)" "  sha256 \"$fixed_sha\""
expect_eq "첫 릴리즈 실행: 푸시 순서" "$(tr '\n' '|' < "$work/push.log")" "push origin main|push origin v1.0.0|push origin main|"
expect_eq "첫 릴리즈 실행: 릴리즈 생성" "$(grep -c '^release create v1.0.0 ' "$work/gh.log")" "1"

echo "release-selftest: $total checks, $failures failures"
((failures == 0))
