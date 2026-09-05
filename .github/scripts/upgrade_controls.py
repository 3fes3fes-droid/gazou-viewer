from pathlib import Path

path = Path("app/src/main/java/jp/gazou/viewer/MainActivity.java")
source = path.read_text(encoding="utf-8")

MARKER = "SLIDESHOW_DEFAULT_MS"
if MARKER in source:
    print("controls already upgraded")
    raise SystemExit(0)


def replace_once(old: str, new: str, label: str) -> None:
    global source
    if old not in source:
        raise SystemExit(f"missing patch target: {label}")
    source = source.replace(old, new, 1)


replace_once(
    "import java.util.Locale;\nimport java.util.Set;",
    "import java.util.Locale;\nimport java.util.Random;\nimport java.util.Set;",
    "Random import",
)

replace_once(
    """    private int currentIndex = 0;\n    private boolean twoPage = false;\n    private int libraryGeneration = 0;\n""",
    """    private static final long SLIDESHOW_DEFAULT_MS = 3000L;\n    private static final long SLIDESHOW_STEP_MS = 500L;\n    private static final long SLIDESHOW_MIN_MS = 500L;\n    private static final long SLIDESHOW_MAX_MS = 10000L;\n\n    private int currentIndex = 0;\n    private boolean twoPage = false;\n    private boolean slideshowRunning = false;\n    private long slideshowIntervalMs = SLIDESHOW_DEFAULT_MS;\n    private boolean randomMode = false;\n    private final Random random = new Random();\n    private final List<Integer> randomOrder = new ArrayList<>();\n    private final Set<Integer> randomUnused = new HashSet<>();\n    private final List<int[]> randomHistory = new ArrayList<>();\n    private int randomOrderCursor = 0;\n    private int randomHistoryPosition = -1;\n    private int randomSecondIndex = -1;\n    private int libraryGeneration = 0;\n\n    private final Runnable slideshowRunnable = () -> {\n        if (!slideshowRunning) {\n            return;\n        }\n        if (!moveNext()) {\n            stopSlideshow();\n            return;\n        }\n        scheduleSlideshowTick();\n    };\n""",
    "state fields",
)

replace_once(
    """        openedUri = intent.getData();\n        twoPage = false;\n        currentIndex = 0;\n        folderImages = Collections.singletonList(new ImageEntry(openedUri, safeDisplayName(openedUri), null, null, -1L, -1L));\n""",
    """        stopSlideshow();\n        randomMode = false;\n        clearRandomSession();\n        openedUri = intent.getData();\n        twoPage = false;\n        currentIndex = 0;\n        folderImages = Collections.singletonList(new ImageEntry(openedUri, safeDisplayName(openedUri), null, null, -1L, -1L));\n""",
    "intent reset",
)

replace_once(
    """                    folderImages = result.entries;\n                    currentIndex = result.openedIndex;\n                    renderCurrentPage();\n""",
    """                    folderImages = result.entries;\n                    currentIndex = result.openedIndex;\n                    if (randomMode) {\n                        restartRandomSessionAtCurrent();\n                    } else {\n                        renderCurrentPage();\n                    }\n""",
    "folder indexing",
)

start = source.find("    @Override\n    public boolean dispatchKeyEvent(KeyEvent event) {")
end = source.find("    private String safeDisplayName(Uri uri) {", start)
if start < 0 or end < 0:
    raise SystemExit("missing control block")

control_block = r'''    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                stopSlideshow();
                movePrevious();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                stopSlideshow();
                moveNext();
                return true;
            case KeyEvent.KEYCODE_SPACE:
                if (event.getRepeatCount() == 0) {
                    toggleSlideshow();
                }
                return true;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                if (event.getRepeatCount() == 0) {
                    toggleTwoPage();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (event.getRepeatCount() == 0) {
                    adjustSlideshowInterval(-SLIDESHOW_STEP_MS);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (event.getRepeatCount() == 0) {
                    adjustSlideshowInterval(SLIDESHOW_STEP_MS);
                }
                return true;
            case KeyEvent.KEYCODE_R:
                if (event.getRepeatCount() == 0) {
                    toggleRandomMode();
                }
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
                stopSlideshow();
                finishAndRemoveTask();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void toggleSlideshow() {
        if (slideshowRunning) {
            stopSlideshow();
            return;
        }
        if (folderImages.isEmpty()) {
            return;
        }
        slideshowRunning = true;
        scheduleSlideshowTick();
    }

    private void scheduleSlideshowTick() {
        if (imageSurface == null) {
            return;
        }
        imageSurface.removeCallbacks(slideshowRunnable);
        if (slideshowRunning) {
            imageSurface.postDelayed(slideshowRunnable, slideshowIntervalMs);
        }
    }

    private void stopSlideshow() {
        slideshowRunning = false;
        if (imageSurface != null) {
            imageSurface.removeCallbacks(slideshowRunnable);
        }
    }

    private void adjustSlideshowInterval(long deltaMs) {
        slideshowIntervalMs = Math.max(
                SLIDESHOW_MIN_MS,
                Math.min(SLIDESHOW_MAX_MS, slideshowIntervalMs + deltaMs));
        if (slideshowRunning) {
            scheduleSlideshowTick();
        }
    }

    private void toggleTwoPage() {
        twoPage = !twoPage;
        randomSecondIndex = -1;
        if (randomMode) {
            restartRandomSessionAtCurrent();
        } else {
            renderCurrentPage();
        }
    }

    private void toggleRandomMode() {
        randomMode = !randomMode;
        if (randomMode) {
            restartRandomSessionAtCurrent();
        } else {
            clearRandomSession();
            renderCurrentPage();
        }
    }

    private void clearRandomSession() {
        randomOrder.clear();
        randomUnused.clear();
        randomHistory.clear();
        randomOrderCursor = 0;
        randomHistoryPosition = -1;
        randomSecondIndex = -1;
    }

    private void restartRandomSessionAtCurrent() {
        clearRandomSession();
        if (folderImages.isEmpty() || currentIndex < 0 || currentIndex >= folderImages.size()) {
            renderCurrentPage();
            return;
        }

        for (int i = 0; i < folderImages.size(); i++) {
            randomOrder.add(i);
            randomUnused.add(i);
        }
        Collections.shuffle(randomOrder, random);

        randomUnused.remove(currentIndex);
        int second = -1;
        if (twoPage && currentIndex + 1 < folderImages.size()) {
            second = currentIndex + 1;
            randomUnused.remove(second);
        }
        randomSecondIndex = second;
        randomHistory.add(new int[]{currentIndex, second});
        randomHistoryPosition = 0;
        renderCurrentPage();
    }

    private boolean movePrevious() {
        if (randomMode) {
            if (randomHistoryPosition <= 0) {
                return false;
            }
            randomHistoryPosition--;
            applyRandomHistoryPosition();
            return true;
        }

        int step = twoPage ? 2 : 1;
        int candidate = currentIndex - step;
        if (candidate < 0) {
            return false;
        }
        currentIndex = candidate;
        renderCurrentPage();
        return true;
    }

    private boolean moveNext() {
        if (randomMode) {
            return moveNextRandom();
        }

        int step = twoPage ? 2 : 1;
        int candidate = currentIndex + step;
        if (candidate >= folderImages.size()) {
            return false;
        }
        currentIndex = candidate;
        renderCurrentPage();
        return true;
    }

    private boolean moveNextRandom() {
        if (randomHistoryPosition + 1 < randomHistory.size()) {
            randomHistoryPosition++;
            applyRandomHistoryPosition();
            return true;
        }

        int base = -1;
        while (randomOrderCursor < randomOrder.size()) {
            int candidate = randomOrder.get(randomOrderCursor++);
            if (randomUnused.remove(candidate)) {
                base = candidate;
                break;
            }
        }
        if (base < 0) {
            return false;
        }

        int second = -1;
        if (twoPage && base + 1 < folderImages.size() && randomUnused.remove(base + 1)) {
            second = base + 1;
        }

        randomHistory.add(new int[]{base, second});
        randomHistoryPosition = randomHistory.size() - 1;
        applyRandomHistoryPosition();
        return true;
    }

    private void applyRandomHistoryPosition() {
        if (randomHistoryPosition < 0 || randomHistoryPosition >= randomHistory.size()) {
            return;
        }
        int[] position = randomHistory.get(randomHistoryPosition);
        currentIndex = position[0];
        randomSecondIndex = position[1];
        renderCurrentPage();
    }

    private void renderCurrentPage() {
        if (folderImages.isEmpty() || currentIndex < 0 || currentIndex >= folderImages.size()) {
            imageSurface.show(null, null, false);
            return;
        }

        Uri current = folderImages.get(currentIndex).uri;
        if (!twoPage) {
            imageSurface.show(current, null, false);
            return;
        }

        int partnerIndex = randomMode ? randomSecondIndex : currentIndex + 1;
        if (partnerIndex >= 0
                && partnerIndex < folderImages.size()
                && partnerIndex != currentIndex) {
            // 漫画の右開き。現在ページを右、次ページを左へ置く。
            Uri left = folderImages.get(partnerIndex).uri;
            Uri right = current;
            imageSurface.show(left, right, true);
            return;
        }

        // 2枚モードでも相方が無い末尾ページは1枚で表示する。
        imageSurface.show(current, null, false);
    }

'''
source = source[:start] + control_block + source[end:]

replace_once(
    """    protected void onDestroy() {\n        libraryGeneration++;\n        libraryExecutor.shutdownNow();\n""",
    """    protected void onDestroy() {\n        stopSlideshow();\n        libraryGeneration++;\n        libraryExecutor.shutdownNow();\n""",
    "onDestroy",
)

source = source.replace(
    " * UI は持たず、← / → / Space / Esc のみを扱う。",
    " * UI は持たず、← / → / Space / Ctrl / ↑ / ↓ / R / Esc を扱う。",
    1,
)

required = [
    "SLIDESHOW_DEFAULT_MS",
    "KEYCODE_CTRL_LEFT",
    "KEYCODE_R",
    "toggleSlideshow",
    "restartRandomSessionAtCurrent",
    "Collections.shuffle(randomOrder, random)",
]
for token in required:
    if token not in source:
        raise SystemExit(f"upgrade verification failed: {token}")

path.write_text(source, encoding="utf-8")
print("controls upgraded")
