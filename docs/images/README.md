# 예시 그림

README 가 쓰는 그림 목록입니다. 모두 `UiSnapshotTest` 가 헤드리스로 렌더해 `app/build/ui-snapshots/` 에 쓴 PNG 를 복사한 것이며, 실제 세션을 캡처한 화면이 아닙니다.

- `editor.png` 프로젝트 트리와 Kotlin 에디터 (`02-editor-kotlin.png`)
- `editor-light.png` 라이트 외관의 프로젝트 트리와 Kotlin 에디터 (`02-editor-kotlin-light.png`)
- `search.png` Search Everywhere 결과 (`04-search.png`)
- `find.png` 찾기 바 (`08-find-bar.png`)
- `terminal.png` 터미널 (`10-terminal.png`)
- `run.png` Run 창 (`11-run-window.png`)
- `glass.png` 코드 위에 뜬 Search Everywhere 유리 팝업 (`13-search-over-code.png`)

한국어판과 영어판 README 가 같은 그림을 씁니다. `UiSnapshotTest` 의 시나리오가 바뀌면 `app/build/ui-snapshots/` 의 PNG 를 이 디렉터리로 다시 복사합니다.

스냅샷은 창 없이 오프스크린으로 렌더되므로 앱 안에서 그리는 유리가 찍히고, 네이티브 유리가 데스크톱을 비추는 모습은 그림에 나타나지 않습니다.
