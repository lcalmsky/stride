# stride 저장소 규칙

## 변경을 마무리하기 전 확인 순서

어떤 작업이든 main 에 올리기 전에 README, 예시 그림, 릴리즈를 이 순서로 확인한다.

1. 코드 변경을 커밋한다. 아직 푸시하지 않는다.
2. 바뀐 동작을 아래 「README 대조표」에서 찾아 `README.md` 와 `README.en.md` 의 해당 절을 함께 고친다. 맞는 절이 없는 새 기능이면 두 파일에 절을 추가한다.
3. 바뀐 파일을 아래 「그림 대조표」에서 찾아 해당 그림을 다시 만든다. `./gradlew test` 로 `UiSnapshotTest` 를 돌려 `app/build/ui-snapshots/` 에 PNG 를 새로 쓰고, 쓰인 파일을 `docs/images/` 로 같은 이름으로 복사한다. 그림을 추가하거나 지우면 `docs/images/README.md` 목록을 고친다.
4. README 와 그림을 `docs:` 로 커밋한다.
5. `feat` 나 `fix` 가 있으면 마지막에 `scripts/release.sh` 를 실행한다. 스크립트가 직접 푸시하므로 코드·README·그림·릴리즈가 같은 푸시에 들어가도록 이 순서를 지킨다. 릴리즈 대상이 아니면 여기서 푸시한다.

작업을 보고할 때는 README, 그림, 릴리즈 세 항목마다 한 일 또는 하지 않은 이유를 한 줄씩 적는다.

### README 대조표

| 바뀐 것 | 고칠 README 절 |
|---|---|
| 프로젝트 트리, 탭, 에디터, 상태 바 | 기능 › 에디터, 사용법 › 키보드 |
| Search Everywhere, Find in Path, 최근 파일, 파일로 이동 | 기능 › 검색 |
| 찾기 바와 바꾸기 | 기능 › 찾기, 사용법 › 키보드 |
| Gradle 테스트 실행과 Run 창 | 기능 › Run, 사용법 › 키보드 |
| 터미널 | 기능 › 터미널, 사용법 › 키보드 |
| 색·타이포·네온·글래스·애니메이션 | 기능 › 리퀴드 글래스 UI |
| 단축키와 키맵 | 사용법 › 키보드 |
| 모듈 구조, 확장점, 디스크립터 | 동작 방식 |
| 설치, 필요 조건, 빌드·테스트·릴리즈 | 설치, 개발 |

### 그림 대조표

| 바뀐 파일 | 다시 복사할 그림 |
|---|---|
| `app/.../ui/App.kt` 의 트리·탭·에디터 | `editor.png` |
| `app/.../search/` 의 결과 창과 찾기 결과 | `search.png` |
| `app/.../editor/find/FindBar.kt` | `find.png` |
| `app/.../run/` 의 Run 창과 거터 표시 | `run.png` |
| `app/.../terminal/` 의 터미널 화면과 팔레트 | `terminal.png` |
| `app/.../ui/theme/` 의 색·네온·글래스 | 모두, `editor-light.png` |
| 어느 그림에 영향이 있는지 판단이 서지 않을 때 | 모두 |

## 기능이 바뀌면 반드시 릴리즈한다

앱 동작이 바뀌는 변경(`feat`, `fix`)을 main 에 올릴 때는 같은 푸시에서 릴리즈까지 한다. README, 예시 그림만 바뀐 경우는 릴리즈하지 않는다.

버전 정본은 `app/build.gradle.kts` 의 `packageVersion` 한 줄이다. macOS 번들 버전은 major 가 0 이면 안 되므로 1.0.0 부터 올린다.

`feat` 는 마이너, `fix` 는 패치를 올린다. 설정 키나 확장점 계약처럼 기존 환경과 호환이 깨지면 메이저를 올린다.

릴리즈는 `scripts/release.sh X.Y.Z -m "<바뀐 기능 요약>"` 한 번으로 한다. 스크립트가 하는 일은 다음과 같다.

1. 저장소 상태, 이전 버전보다 큰 버전, 겹치지 않는 태그, GitHub 계정과 origin 주소를 검사한다.
2. `app/build.gradle.kts` 의 `packageVersion` 을 새 버전으로 바꾼다.
3. 테스트와 `./gradlew :app:createDistributable` 을 돌린다.
4. 버전 변경을 커밋한다.
5. `vX.Y.Z` 태그를 만들고 main 과 함께 푸시한다.
6. 태그로 GitHub Release 를 만들고 arm64 zip 을 붙인다.
7. zip 의 sha256 을 구한다.
8. `Casks/stride.rb` 의 url 과 sha256 을 새 값으로 바꿔 커밋하고 푸시한다.
9. 릴리즈 URL 과 설치·업데이트 명령을 안내한다.

중간에 멈췄다면 `scripts/release.sh --cask-only X.Y.Z` 로 Cask 갱신부터 다시 한다.

릴리즈 전에 `gh auth switch -u lcalmsky` 로 활성 계정을 맞추고, origin 이 `git@github-lcalmsky:lcalmsky/stride.git` 인지 확인한다.

Homebrew 는 같은 저장소를 tap 으로 쓴다(`Casks/stride.rb`). Cask 를 갱신하지 않으면 `brew upgrade` 가 새 버전을 모른다. 짧은 이름 `stride` 는 다른 tap 과 겹칠 수 있어 `lcalmsky/stride/stride` 로 설치한다. 릴리즈 zip 은 Apple Silicon 에서 빌드한 arm64 전용이다.

## 커밋

- 제목은 `<type>: #<이슈> <명사구>` 로 쓴다. type 은 feat, fix, docs, refactor, chore, test, build 중 하나다.
- 한국어로 쓰고 서술어로 끝내지 않는다. 「~개편」은 되고 「~개편한다」는 안 된다.
- 본문에는 바뀐 점을 항목으로 적는다.
- 브랜치는 `{type}/{이슈번호}` 로 만들고 이슈를 먼저 만든다.
- 릴리즈는 main 에서만 한다.

## 공개 저장소에 사내 정보를 넣지 않는다

이 저장소는 공개다. 코드, 주석, 테스트, 문서, 예시 데이터 어디에도 회사 이름, 사내 도구 이름, 사내 호스트 주소, 실존 인물 이름, 실제 티켓 키, 사용자의 실제 프로젝트 이름과 경로, 이메일을 넣지 않는다. 예시가 필요하면 `api-server`, `web-dashboard` 같은 일반 이름을 쓴다.

외부 제품 이름은 README 의 키맵 호환 문구 외에는 쓰지 않는다.

예시 그림은 반드시 `UiSnapshotTest` 가 만든 가상 프로젝트 화면으로만 만든다. 실제 작업 화면을 캡처해 넣지 않는다.

## 빌드와 테스트

```bash
./gradlew test                          # 실패 0 이어야 하고 건수는 줄어들면 안 된다
./gradlew :app:createDistributable      # app/build/compose/binaries/main/app/strIDE.app
scripts/release-selftest.sh             # release.sh 검사. 실패 0 이어야 한다
```

- `./gradlew test` 는 `UiSnapshotTest` 를 포함해 `app/build/ui-snapshots/` 에 PNG 를 쓴다. 테스트 건수가 줄어들면 안 된다.
- 프레임 시간 테스트는 Gradle 을 동시에 두 개 돌리면 값이 부풀려지므로 단독으로 돌린다.
- 창을 띄우는 `./gradlew :app:run` 은 사람이 직접 실행한다.
