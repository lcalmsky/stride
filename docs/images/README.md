# 예시 그림

README 가 쓰는 그림 목록입니다. 모두 `UiSnapshotTest` 가 헤드리스로 렌더해 `app/build/ui-snapshots/` 에 쓴 PNG 를 복사한 것이며, 실제 세션을 캡처한 화면이 아닙니다.

- `editor.png` 프로젝트 트리와 Kotlin 에디터 (`02-editor-kotlin.png`)
- `editor-light.png` 라이트 외관의 프로젝트 트리와 Kotlin 에디터 (`02-editor-kotlin-light.png`)
- `search.png` Search Everywhere 결과 (`04-search.png`)
- `find.png` 찾기 바 (`08-find-bar.png`)
- `find-window.png` Find 창 (`17-find-window.png`)
- `replace-in-path.png` Replace in Path 팝업 (`18-replace-in-path.png`)
- `terminal.png` 터미널 (`10-terminal.png`)
- `run.png` Run 창 (`11-run-window.png`)
- `glass.png` 코드 위에 뜬 Search Everywhere 유리 팝업 (`13-search-over-code.png`)
- `multi-caret.png` 여러 캐럿으로 같은 단어를 고른 Kotlin 에디터 (`15-multi-caret.png`)
- `folding.png` 함수 본문을 `{...}` 로 접은 Kotlin 에디터 (`16-folding.png`)
- `problems.png` 오류·경고 물결 밑줄과 Problems 창 (`19-problems.png`)
- `show-usages.png` 커서 단어의 사용처 팝업 (`20-show-usages.png`)
- `recent-locations.png` 자리마다 코드 세 줄을 보여 주는 최근 위치 팝업 (`21-recent-locations.png`)
- `navigation-bar.png` 하단 경로 캡슐이 바뀐 탐색 막대와 폴더 하위 항목 목록 (`22-navigation-bar.png`)
- `type-hierarchy.png` Polygon 의 상위·하위 타입을 펼친 Hierarchy 창 (`23-type-hierarchy.png`)
- `refactor-this.png` 지역 변수 이름에서 연 Refactor This 팝업 (`24-refactor-this.png`)
- `generate.png` 제네릭 인터페이스의 멤버를 타입 인자로 치환해 보여 주는 Implement Methods 멤버 선택 팝업 (`25-generate-implement.png`)
- `commit.png` 추가·수정·삭제 거터 표시가 보이는 에디터와 Commit 창 (`26-commit-window.png`)
- `diff.png` 수정한 줄에서 연 HEAD ↔ Local 한 열 diff 팝업 (`27-diff.png`)

한국어판과 영어판 README 가 같은 그림을 씁니다. `UiSnapshotTest` 의 시나리오가 바뀌면 `app/build/ui-snapshots/` 의 PNG 를 이 디렉터리로 다시 복사합니다.

스냅샷은 창 없이 오프스크린으로 렌더되므로 앱 안에서 그리는 유리가 찍히고, 네이티브 유리가 데스크톱을 비추는 모습은 그림에 나타나지 않습니다.
