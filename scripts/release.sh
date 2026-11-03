#!/bin/bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

usage() {
    cat <<'EOF'
사용법:
  scripts/release.sh X.Y.Z -m "<바뀐 기능 요약>" [--notes-file FILE] [--dry-run]
  scripts/release.sh --cask-only X.Y.Z [--dry-run]
  scripts/release.sh --help

X.Y.Z 를 app/build.gradle.kts 의 packageVersion 에 반영하고, 빌드·검사·태그·GitHub Release·Homebrew Cask 갱신까지 한 번에 한다.

  -m "<요약>"        커밋 제목과 릴리즈 본문에 쓸 바뀐 기능 요약
  --notes-file FILE  릴리즈 본문으로 쓸 파일. 없으면 요약과 설치·업데이트 방법으로 만든다
  --cask-only        7~9 단계(zip sha256, Cask 갱신·푸시)만 다시 한다. 태그 vX.Y.Z 가 로컬과 원격에 있어야 한다
  --dry-run          검사만 하고, 이어지는 단계에서 할 일을 출력한다
  --help             이 도움말을 보여 준다
EOF
}

fail() {
    echo "오류: $*" >&2
    exit 1
}

version=""
summary=""
notes_file=""
dry_run=0
cask_only=0

while (($#)); do
    case "$1" in
        --help|-h)
            usage
            exit 0
            ;;
        -m)
            (($# >= 2)) || { echo "오류: -m 뒤에 요약이 필요합니다" >&2; exit 1; }
            summary="$2"
            shift 2
            ;;
        --notes-file)
            (($# >= 2)) || { echo "오류: --notes-file 뒤에 파일 경로가 필요합니다" >&2; exit 1; }
            notes_file="$2"
            shift 2
            ;;
        --dry-run)
            dry_run=1
            shift
            ;;
        --cask-only)
            cask_only=1
            shift
            ;;
        -*)
            echo "오류: 알 수 없는 인자입니다: $1" >&2
            usage >&2
            exit 1
            ;;
        *)
            [[ -z $version ]] || { echo "오류: 버전은 하나만 넘길 수 있습니다: $1" >&2; exit 1; }
            version="$1"
            shift
            ;;
    esac
done

[[ -n $version ]] || { echo "오류: 버전을 넘기세요" >&2; usage >&2; exit 1; }
if ((cask_only)); then
    [[ -z $summary && -z $notes_file ]] || { echo "오류: --cask-only 는 -m 과 --notes-file 을 받지 않습니다" >&2; exit 1; }
else
    [[ -n $summary ]] || { echo "오류: -m 으로 바뀐 기능 요약을 넘기세요" >&2; usage >&2; exit 1; }
fi

step="준비"
notes_tmp=""
bundle="app/build/compose/binaries/main/app/strIDE.app"
old_version=""

on_exit() {
    local status=$?
    if ((status != 0)); then
        echo "실패: ${step} 단계에서 멈췄습니다 (종료 ${status})" >&2
    fi
    if [[ -n $notes_tmp ]]; then
        rm -f "$notes_tmp"
    fi
}
trap on_exit EXIT

begin_step() {
    step="$1"
    echo "==> $1"
}

version_gt() {
    local IFS=.
    local -a left right
    read -r -a left <<< "$1"
    read -r -a right <<< "$2"
    local i l r
    for i in 0 1 2; do
        l="${left[i]:-0}"
        r="${right[i]:-0}"
        if ((10#$l > 10#$r)); then return 0; fi
        if ((10#$l < 10#$r)); then return 1; fi
    done
    return 1
}

update_cask() {
    local expect_local_zip=$1

    # 7. 업로드한 zip 자산의 sha256
    begin_step "7/9 zip sha256"
    step="sha256"
    asset="https://github.com/lcalmsky/stride/releases/download/v${version}/strIDE-${version}-arm64.zip"
    sha=""
    for attempt in 1 2 3 4 5; do
        if sha="$(curl -fsSL "$asset" | shasum -a 256 | awk '{print $1}')" && [[ ${#sha} -eq 64 ]]; then
            break
        fi
        sha=""
        echo "zip 자산을 아직 받지 못했습니다 (${attempt}/5)"
        if ((attempt < 5)); then
            sleep 3
        fi
    done
    [[ ${#sha} -eq 64 ]] || fail "업로드한 zip 자산의 sha256 을 구하지 못했습니다: $asset"

    local_zip="build/release/strIDE-${version}-arm64.zip"
    if [[ -f $local_zip ]]; then
        local_sha="$(shasum -a 256 "$local_zip" | awk '{print $1}')"
        [[ $sha == "$local_sha" ]] || fail "업로드한 zip 자산의 sha256 이 로컬 zip 과 다릅니다: $sha != $local_sha"
    elif ((expect_local_zip)); then
        fail "로컬 zip 이 없습니다: $local_zip"
    fi

    # 8. Cask 갱신
    begin_step "8/9 Cask 갱신"
    step="Cask"
    cask="Casks/stride.rb"
    [[ -f $cask ]] || fail "Cask 파일이 없습니다: $cask"

    version_line="  version \"$version\""
    sha_line="  sha256 \"$sha\""
    version_count="$(grep -cE '^  version "' "$cask" || true)"
    sha_count="$(grep -cE '^  sha256 "' "$cask" || true)"
    [[ $version_count -eq 1 ]] || fail "Cask 의 version 줄이 정확히 하나가 아닙니다: ${version_count}개"
    [[ $sha_count -eq 1 ]] || fail "Cask 의 sha256 줄이 정확히 하나가 아닙니다: ${sha_count}개"

    sed -i '' -E "s|^  version \".*\"$|$version_line|" "$cask"
    sed -i '' -E "s|^  sha256 \".*\"$|$sha_line|" "$cask"

    git commit -am "chore: Cask v$version"
    git push origin main

    # 9. 안내
    begin_step "9/9 마무리"
    step="완료"
    echo "릴리즈: https://github.com/lcalmsky/stride/releases/tag/v$version"
    echo "업데이트: brew upgrade --cask lcalmsky/stride/stride"
}

# 1. 검사
begin_step "1/9 검사"
step="검사"

[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "버전은 X.Y.Z 형식이어야 합니다: $version"

if [[ -n $notes_file && ! -f $notes_file ]]; then
    fail "릴리즈 노트 파일이 없습니다: $notes_file"
fi

branch="$(git branch --show-current)"
[[ $branch == main ]] || fail "현재 브랜치가 main 이 아닙니다: ${branch:-없음}"

worktree="$(git status --porcelain)"
[[ -z $worktree ]] || fail "워킹트리가 깨끗하지 않습니다. 커밋하거나 되돌린 뒤 다시 실행하세요.
$worktree"

if ((cask_only)); then
    [[ -n "$(git tag -l "v$version")" ]] || fail "로컬에 태그 v$version 이 없습니다"
    remote_tag="$(git ls-remote --tags origin "refs/tags/v$version")" || fail "원격 태그를 조회하지 못했습니다"
    [[ -n $remote_tag ]] || fail "원격 origin 에 태그 v$version 이 없습니다"
else
    [[ -z "$(git tag -l "v$version")" ]] || fail "태그 v$version 이 이미 있습니다"

    old_version="$(sed -n 's/.*packageVersion = "\([^"]*\)".*/\1/p' app/build.gradle.kts)"
    old_version="${old_version%%$'\n'*}"
    [[ -n $old_version ]] || fail "app/build.gradle.kts 에서 packageVersion 을 찾지 못했습니다"
    if [[ -z "$(git tag -l 'v*')" ]]; then
        # 첫 릴리즈는 같은 버전을 허용한다. macOS 번들 버전이 0.x 로 시작하면 안 되므로 1.0.0 을 그대로 낸다.
        [[ $version == "$old_version" ]] || version_gt "$version" "$old_version" ||
            fail "새 버전 $version 이 현재 버전 $old_version 보다 작습니다"
    else
        version_gt "$version" "$old_version" || fail "새 버전 $version 이 현재 버전 $old_version 보다 크지 않습니다"
    fi
fi

if ! login="$(gh api user -q .login 2>/dev/null)"; then
    login=""
fi
[[ $login == lcalmsky ]] || fail "gh 활성 계정이 lcalmsky 가 아닙니다: ${login:-없음}. gh auth switch -u lcalmsky 로 바꾸세요"

origin="$(git remote get-url origin 2>/dev/null || true)"
[[ $origin == "git@github-lcalmsky:lcalmsky/stride.git" ]] || fail "origin 주소가 다릅니다: ${origin:-없음}"

if ((cask_only)); then
    echo "검사 통과: 태그 v$version 이 로컬과 원격에 있음, 계정 $login"
else
    echo "검사 통과: $old_version -> $version, 계정 $login"
fi

if ((dry_run)); then
    echo
    echo "--dry-run: 아래 단계는 실행하지 않고 출력만 합니다."
    if ((! cask_only)); then
        if [[ $version == "$old_version" ]]; then
            echo "2. packageVersion 이 이미 $version 이라 버전 변경을 건너뛴다 (첫 릴리즈)"
        else
            echo "2. app/build.gradle.kts 의 packageVersion = \"$old_version\" 을 \"$version\" 으로 바꾼다"
        fi
        echo "3. ./gradlew test --console=plain 과 ./gradlew :app:createDistributable --console=plain 을 돌리고 $bundle/Contents/MacOS/strIDE 를 확인한다. 실패하면 버전 변경을 되돌린다"
        if [[ $version == "$old_version" ]]; then
            echo "4. 바뀐 파일이 없어 커밋을 건너뛴다"
        else
            echo "4. git commit -am \"chore: $summary 버전 $version\""
        fi
        echo "5. git tag -a v$version -m \"$summary\" / git push origin main / git push origin v$version"
        if [[ -n $notes_file ]]; then
            echo "6. $bundle 을 build/release/strIDE-$version-arm64.zip 으로 묶고 gh release create v$version --title v$version --notes-file $notes_file 에 자산으로 붙인다"
        else
            echo "6. $bundle 을 build/release/strIDE-$version-arm64.zip 으로 묶고 gh release create v$version --title v$version --notes-file <요약과 설치·업데이트 방법으로 만든 임시 파일> 에 자산으로 붙인다"
        fi
    fi
    echo "7. https://github.com/lcalmsky/stride/releases/download/v$version/strIDE-$version-arm64.zip 의 sha256 을 구한다 (최대 5회, 3초 간격)"
    echo "8. Casks/stride.rb 의 version 과 sha256 을 갱신하고 git commit -am \"chore: Cask v$version\" 후 푸시한다"
    echo "9. 릴리즈 URL 과 brew upgrade --cask lcalmsky/stride/stride 를 안내한다"
    exit 0
fi

if ((cask_only)); then
    update_cask 0
    exit 0
fi

# 2. 버전 변경
begin_step "2/9 packageVersion $old_version -> $version"
step="버전 변경"

version_changed=0
if [[ $old_version == "$version" ]]; then
    echo "packageVersion 이 이미 $version 이라 버전 변경을 건너뜁니다"
else
    old_pattern="${old_version//./\\.}"
    sed -i '' -E "s|(packageVersion = \")${old_pattern}\"|\1${version}\"|" app/build.gradle.kts

    numstat="$(git diff --numstat -- app/build.gradle.kts)"
    added=""
    deleted=""
    path=""
    read -r added deleted path <<< "$numstat" || true
    if [[ $added != 1 || $deleted != 1 || $path != app/build.gradle.kts ]]; then
        git checkout -- app/build.gradle.kts
        fail "app/build.gradle.kts 에서 정확히 한 줄만 바뀌지 않았습니다: ${numstat:-변경 없음}"
    fi
    version_changed=1
fi

revert_version() {
    if ((version_changed)); then
        git checkout -- app/build.gradle.kts
        version_changed=0
    fi
}

# 3. 빌드와 검사
begin_step "3/9 빌드와 검사"
step="테스트"
if ! ./gradlew test --console=plain; then
    revert_version
    fail "테스트가 실패해 버전 변경을 되돌렸습니다"
fi

step="패키징"
if ! ./gradlew :app:createDistributable --console=plain; then
    revert_version
    fail "패키징이 실패해 버전 변경을 되돌렸습니다"
fi

step="번들 확인"
[[ -x "$bundle/Contents/MacOS/strIDE" ]] || { revert_version; fail "번들이 만들어지지 않았습니다: $bundle/Contents/MacOS/strIDE"; }
echo "번들 확인: $bundle/Contents/MacOS/strIDE"

# 4. 커밋
begin_step "4/9 커밋"
step="커밋"
if ((version_changed)); then
    git commit -am "chore: $summary 버전 $version"
else
    echo "바뀐 파일이 없어 커밋을 건너뜁니다"
fi

# 5. 태그와 푸시
begin_step "5/9 태그와 푸시"
step="태그"
git tag -a "v$version" -m "$summary"
step="푸시"
git push origin main
git push origin "v$version"

# 6. GitHub Release
begin_step "6/9 GitHub Release"
step="zip"
zip_path="build/release/strIDE-$version-arm64.zip"
mkdir -p build/release
ditto -c -k --sequesterRsrc --keepParent "$bundle" "$zip_path"

step="릴리즈"
if [[ -z $notes_file ]]; then
    notes_tmp="$(mktemp "${TMPDIR:-/tmp}/stride-release-notes.XXXXXX")"
    cat > "$notes_tmp" <<EOF
$summary

## 설치

\`\`\`bash
brew tap lcalmsky/stride https://github.com/lcalmsky/stride
brew trust lcalmsky/stride
brew install --cask lcalmsky/stride/stride
\`\`\`

## 업데이트

\`\`\`bash
brew upgrade --cask lcalmsky/stride/stride
\`\`\`
EOF
    notes_file="$notes_tmp"
fi
gh release create "v$version" --title "v$version" --notes-file "$notes_file" "$zip_path"

update_cask 1
