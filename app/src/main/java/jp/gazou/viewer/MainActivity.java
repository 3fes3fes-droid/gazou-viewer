package jp.gazou.viewer;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 画像だけを表示する Chromebook 向け最小ビューア。
 * UI は持たず、← / → / Space / Esc のみを扱う。
 */
public final class MainActivity extends Activity {
    private static final int REQUEST_MEDIA_PERMISSION = 1001;

    private final ExecutorService libraryExecutor = Executors.newSingleThreadExecutor();
    private ImageViewSurface imageSurface;

    private Uri openedUri;
    private List<ImageEntry> folderImages = Collections.emptyList();
    private int currentIndex = 0;
    private boolean twoPage = false;
    private int libraryGeneration = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        imageSurface = new ImageViewSurface();
        setContentView(imageSurface);
        applyImmersiveMode();
        handleViewIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleViewIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveMode();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
        }
    }

    @Override
    public void onTopResumedActivityChanged(boolean isTopResumedActivity) {
        super.onTopResumedActivityChanged(isTopResumedActivity);
        if (isTopResumedActivity) {
            applyImmersiveMode();
            // API 34+ の desktop windowing では、ユーザーが画像を開いた操作に続けて
            // 現在の Activity 自体を全画面へ移行する。非対応・拒否時は無視する。
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    requestFullscreenMode(FULLSCREEN_MODE_REQUEST_ENTER, null);
                } catch (RuntimeException ignored) {
                    // ChromeOS / Android 実装が要求を受け付けない場合も画像表示は継続する。
                }
            }
        }
    }

    private void applyImmersiveMode() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    private void handleViewIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) {
            finishAndRemoveTask();
            return;
        }

        openedUri = intent.getData();
        twoPage = false;
        currentIndex = 0;
        folderImages = Collections.singletonList(new ImageEntry(openedUri, safeDisplayName(openedUri), null, null, -1L, -1L));
        renderCurrentPage(); // 受け取った URI は権限ダイアログより先に即表示する。

        int generation = ++libraryGeneration;
        if (hasMediaPermission()) {
            indexFolderAsync(openedUri, generation);
        } else {
            requestMediaPermission();
        }
    }

    private boolean hasMediaPermission() {
        String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMediaPermission() {
        String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        requestPermissions(new String[]{permission}, REQUEST_MEDIA_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        applyImmersiveMode();
        if (requestCode == REQUEST_MEDIA_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && openedUri != null) {
            indexFolderAsync(openedUri, ++libraryGeneration);
        }
    }

    private void indexFolderAsync(final Uri sourceUri, final int generation) {
        libraryExecutor.execute(() -> {
            FolderResult result = MediaLibrary.resolveFolder(MainActivity.this, sourceUri);
            runOnUiThread(() -> {
                if (generation != libraryGeneration || !sourceUri.equals(openedUri)) {
                    return;
                }
                if (result != null && !result.entries.isEmpty() && result.openedIndex >= 0) {
                    folderImages = result.entries;
                    currentIndex = result.openedIndex;
                    renderCurrentPage();
                }
            });
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                previous();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                next();
                return true;
            case KeyEvent.KEYCODE_SPACE:
                if (event.getRepeatCount() == 0) {
                    twoPage = !twoPage;
                    renderCurrentPage();
                }
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
                finishAndRemoveTask();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void previous() {
        int step = twoPage ? 2 : 1;
        int candidate = currentIndex - step;
        if (candidate >= 0) {
            currentIndex = candidate;
            renderCurrentPage();
        }
    }

    private void next() {
        int step = twoPage ? 2 : 1;
        int candidate = currentIndex + step;
        if (candidate < folderImages.size()) {
            currentIndex = candidate;
            renderCurrentPage();
        }
    }

    private void renderCurrentPage() {
        if (folderImages.isEmpty() || currentIndex < 0 || currentIndex >= folderImages.size()) {
            imageSurface.show(null, null, false);
            return;
        }

        Uri left = folderImages.get(currentIndex).uri;
        Uri right = null;
        boolean split = false;
        if (twoPage && currentIndex + 1 < folderImages.size()) {
            right = folderImages.get(currentIndex + 1).uri;
            split = true;
        }
        // 2枚モードの末尾が1枚だけなら、その1枚を全画面幅で表示する。
        imageSurface.show(left, right, split);
    }

    private String safeDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int i = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && !cursor.isNull(i)) {
                    return cursor.getString(i);
                }
            }
        } catch (RuntimeException ignored) {
        }
        String segment = uri.getLastPathSegment();
        return segment == null ? "" : segment;
    }

    @Override
    protected void onDestroy() {
        libraryGeneration++;
        libraryExecutor.shutdownNow();
        if (imageSurface != null) {
            imageSurface.shutdown();
        }
        super.onDestroy();
    }

    /** 黒背景へ1枚または2枚だけ描画する。 */
    private final class ImageViewSurface extends View {
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final ThreadPoolExecutor decoderExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        private final AtomicInteger decodeGeneration = new AtomicInteger();

        private Uri requestedLeft;
        private Uri requestedRight;
        private boolean requestedSplit;
        private Bitmap leftBitmap;
        private Bitmap rightBitmap;

        ImageViewSurface() {
            super(MainActivity.this);
            setBackgroundColor(Color.BLACK);
            setFocusable(true);
            setFocusableInTouchMode(true);
            requestFocus();
        }

        void show(Uri left, Uri right, boolean split) {
            requestedLeft = left;
            requestedRight = right;
            requestedSplit = split;
            startDecode();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w > 0 && h > 0 && (w != oldw || h != oldh)) {
                startDecode();
            }
        }

        private void startDecode() {
            final int w = getWidth();
            final int h = getHeight();
            if (w <= 0 || h <= 0) {
                invalidate();
                return;
            }

            final Uri left = requestedLeft;
            final Uri right = requestedRight;
            final boolean split = requestedSplit && right != null;
            final int generation = decodeGeneration.incrementAndGet();

            decoderExecutor.getQueue().clear();
            leftBitmap = null;
            rightBitmap = null;
            invalidate();

            if (left != null) {
                int cellWidth = split ? Math.max(1, w / 2) : w;
                decodeAsync(left, cellWidth, h, generation, true);
            }
            if (split) {
                int cellWidth = Math.max(1, w - (w / 2));
                decodeAsync(right, cellWidth, h, generation, false);
            }
        }

        private void decodeAsync(final Uri uri, final int maxWidth, final int maxHeight,
                                 final int generation, final boolean left) {
            decoderExecutor.execute(() -> {
                Bitmap bitmap = decodeBitmap(uri, maxWidth, maxHeight);
                post(() -> {
                    if (generation != decodeGeneration.get()) {
                        return;
                    }
                    if (left) {
                        leftBitmap = bitmap;
                    } else {
                        rightBitmap = bitmap;
                    }
                    invalidate();
                });
            });
        }

        private Bitmap decodeBitmap(Uri uri, int maxWidth, int maxHeight) {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
                return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                    int sourceWidth = info.getSize().getWidth();
                    int sourceHeight = info.getSize().getHeight();
                    if (sourceWidth <= 0 || sourceHeight <= 0) {
                        return;
                    }
                    double scale = Math.min((double) maxWidth / sourceWidth,
                            (double) maxHeight / sourceHeight);
                    if (scale < 1.0) {
                        int targetWidth = Math.max(1, (int) Math.floor(sourceWidth * scale));
                        int targetHeight = Math.max(1, (int) Math.floor(sourceHeight * scale));
                        decoder.setTargetSize(targetWidth, targetHeight);
                    }
                });
            } catch (Exception | OutOfMemoryError ignored) {
                return null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.BLACK);
            int w = getWidth();
            int h = getHeight();

            if (requestedSplit) {
                int middle = w / 2;
                drawFit(canvas, leftBitmap, 0, 0, middle, h);
                drawFit(canvas, rightBitmap, middle, 0, w, h);
            } else {
                drawFit(canvas, leftBitmap, 0, 0, w, h);
            }
        }

        private void drawFit(Canvas canvas, Bitmap bitmap, int left, int top, int right, int bottom) {
            if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
                return;
            }

            float cellWidth = right - left;
            float cellHeight = bottom - top;
            float scale = Math.min(cellWidth / bitmap.getWidth(), cellHeight / bitmap.getHeight());
            float drawWidth = bitmap.getWidth() * scale;
            float drawHeight = bitmap.getHeight() * scale;
            float x = left + (cellWidth - drawWidth) / 2f;
            float y = top + (cellHeight - drawHeight) / 2f;

            RectF destination = new RectF(x, y, x + drawWidth, y + drawHeight);
            canvas.drawBitmap(bitmap, null, destination, bitmapPaint);
        }

        void shutdown() {
            decodeGeneration.incrementAndGet();
            decoderExecutor.shutdownNow();
        }
    }

    private static final class ImageEntry {
        final Uri uri;
        final String name;
        final String volume;
        final String relativePath;
        final long size;
        final long modifiedSeconds;

        ImageEntry(Uri uri, String name, String volume, String relativePath,
                   long size, long modifiedSeconds) {
            this.uri = uri;
            this.name = name == null ? "" : name;
            this.volume = volume;
            this.relativePath = relativePath;
            this.size = size;
            this.modifiedSeconds = modifiedSeconds;
        }
    }

    private static final class FolderResult {
        final List<ImageEntry> entries;
        final int openedIndex;

        FolderResult(List<ImageEntry> entries, int openedIndex) {
            this.entries = entries;
            this.openedIndex = openedIndex;
        }
    }

    /** MediaStore 上で「開いた画像が入っているフォルダ」だけを解決する。 */
    private static final class MediaLibrary {
        private static final Comparator<ImageEntry> NAME_ORDER =
                (a, b) -> naturalCompare(a.name, b.name);

        static FolderResult resolveFolder(Activity activity, Uri opened) {
            ContentResolver resolver = activity.getContentResolver();

            // 1) MediaStore URI そのもの、または DocumentsProvider -> MediaStore の正規変換。
            ImageEntry direct = queryAsMediaEntry(resolver, opened);
            if (direct == null && Build.VERSION.SDK_INT >= 29) {
                try {
                    Uri mediaUri = DocumentsContract.getMediaUri(resolver, opened);
                    if (mediaUri != null) {
                        direct = queryAsMediaEntry(resolver, mediaUri);
                    }
                } catch (RuntimeException ignored) {
                }
            }
            if (direct != null && direct.relativePath != null && direct.volume != null) {
                return loadExactFolder(resolver, direct, opened);
            }

            SourceMetadata source = querySourceMetadata(resolver, opened);

            // 2) ChromeOS Files が ARC へ渡す URI はパスを保持する。
            //    download/...       -> Android MediaStore の Download/...
            //    external_files/... -> Android shared storage の相対パス
            if (Build.VERSION.SDK_INT >= 29) {
                ChromePathHint hint = ChromePathHint.from(opened);
                if (hint != null) {
                    FolderResult byChromePath = loadByChromePath(resolver, opened, source, hint);
                    if (byChromePath != null) {
                        return byChromePath;
                    }
                }
            }

            // 3) Provider 固有 URI の一般フォールバック。
            //    名前 + サイズで候補を絞り、複数なら内容ハッシュと URI パス情報も照合する。
            if (Build.VERSION.SDK_INT >= 29) {
                return findByMetadataAcrossVolumes(resolver, opened, source);
            }
            return findLegacyApi28(resolver, opened, source);
        }

        private static ImageEntry queryAsMediaEntry(ContentResolver resolver, Uri uri) {
            if (Build.VERSION.SDK_INT < 29) {
                return null;
            }
            String[] projection = {
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.VOLUME_NAME,
                    MediaStore.Images.Media.RELATIVE_PATH,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_MODIFIED
            };
            try (Cursor c = resolver.query(uri, projection, null, null, null)) {
                if (c == null || !c.moveToFirst()) {
                    return null;
                }
                long id = getLong(c, MediaStore.Images.Media._ID, -1L);
                String name = getString(c, MediaStore.Images.Media.DISPLAY_NAME);
                String volume = getString(c, MediaStore.Images.Media.VOLUME_NAME);
                String path = getString(c, MediaStore.Images.Media.RELATIVE_PATH);
                long size = getLong(c, MediaStore.Images.Media.SIZE, -1L);
                long modified = getLong(c, MediaStore.Images.Media.DATE_MODIFIED, -1L);
                if (id < 0 || volume == null) {
                    return null;
                }
                Uri collection = MediaStore.Images.Media.getContentUri(volume);
                return new ImageEntry(ContentUris.withAppendedId(collection, id), name, volume, path, size, modified);
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private static FolderResult loadByChromePath(ContentResolver resolver, Uri opened,
                                                       SourceMetadata source, ChromePathHint hint) {
            Set<String> volumes;
            try {
                volumes = MediaStore.getExternalVolumeNames(resolver.getContext());
            } catch (RuntimeException e) {
                return null;
            }

            for (String volume : volumes) {
                List<ImageEntry> entries = queryFolderApi29(resolver, volume, hint.relativeFolder);
                if (entries.isEmpty()) {
                    continue;
                }
                int index = selectOpenedIndex(resolver, opened, source, entries, hint.fileName, hint.relativeFolder);
                if (index >= 0) {
                    Collections.sort(entries, NAME_ORDER);
                    index = indexOfOpenedAfterSort(resolver, opened, source, entries, hint.fileName, hint.relativeFolder);
                    if (index >= 0) {
                        return new FolderResult(entries, index);
                    }
                }
            }
            return null;
        }

        private static FolderResult loadExactFolder(ContentResolver resolver, ImageEntry direct, Uri originalOpened) {
            List<ImageEntry> entries = queryFolderApi29(resolver, direct.volume, direct.relativePath);
            if (entries.isEmpty()) {
                return null;
            }
            Collections.sort(entries, NAME_ORDER);
            for (int i = 0; i < entries.size(); i++) {
                ImageEntry e = entries.get(i);
                if (e.uri.equals(direct.uri)) {
                    return new FolderResult(entries, i);
                }
                if (sameStableIdentity(e, direct)) {
                    return new FolderResult(entries, i);
                }
            }
            SourceMetadata source = querySourceMetadata(resolver, originalOpened);
            int i = selectOpenedIndex(resolver, originalOpened, source, entries, direct.name, direct.relativePath);
            return i >= 0 ? new FolderResult(entries, i) : null;
        }

        private static boolean sameStableIdentity(ImageEntry a, ImageEntry b) {
            return safeEquals(a.volume, b.volume)
                    && safeEquals(a.relativePath, b.relativePath)
                    && safeEquals(a.name, b.name)
                    && (a.size < 0 || b.size < 0 || a.size == b.size);
        }

        private static List<ImageEntry> queryFolderApi29(ContentResolver resolver, String volume, String relativePath) {
            if (Build.VERSION.SDK_INT < 29 || volume == null) {
                return Collections.emptyList();
            }
            Uri collection = MediaStore.Images.Media.getContentUri(volume);
            String[] projection = {
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.RELATIVE_PATH
            };

            String selection;
            String[] args;
            if (relativePath == null) {
                selection = MediaStore.Images.Media.RELATIVE_PATH + " IS NULL";
                args = null;
            } else {
                selection = MediaStore.Images.Media.RELATIVE_PATH + "=?";
                args = new String[]{relativePath};
            }

            List<ImageEntry> out = new ArrayList<>();
            try (Cursor c = resolver.query(collection, projection, selection, args, null)) {
                if (c == null) {
                    return out;
                }
                while (c.moveToNext()) {
                    String name = getString(c, MediaStore.Images.Media.DISPLAY_NAME);
                    if (!isSupportedName(name)) {
                        continue;
                    }
                    long id = getLong(c, MediaStore.Images.Media._ID, -1L);
                    if (id < 0) {
                        continue;
                    }
                    long size = getLong(c, MediaStore.Images.Media.SIZE, -1L);
                    long modified = getLong(c, MediaStore.Images.Media.DATE_MODIFIED, -1L);
                    String actualPath = getString(c, MediaStore.Images.Media.RELATIVE_PATH);
                    out.add(new ImageEntry(ContentUris.withAppendedId(collection, id), name, volume,
                            actualPath, size, modified));
                }
            } catch (RuntimeException ignored) {
                return Collections.emptyList();
            }
            return out;
        }

        private static FolderResult findByMetadataAcrossVolumes(ContentResolver resolver, Uri opened,
                                                                  SourceMetadata source) {
            if (source.name == null || !isSupportedName(source.name)) {
                return null;
            }

            Set<String> volumes;
            try {
                volumes = MediaStore.getExternalVolumeNames(resolver.getContext());
            } catch (RuntimeException e) {
                return null;
            }

            List<ImageEntry> candidates = new ArrayList<>();
            for (String volume : volumes) {
                candidates.addAll(queryCandidates(resolver, volume, source.name, source.size));
            }
            if (candidates.isEmpty()) {
                return null;
            }

            ImageEntry selected = disambiguateCandidate(resolver, opened, source, candidates);
            if (selected == null || selected.relativePath == null || selected.volume == null) {
                return null;
            }
            return loadExactFolder(resolver, selected, opened);
        }

        private static List<ImageEntry> queryCandidates(ContentResolver resolver, String volume,
                                                         String name, long size) {
            Uri collection = MediaStore.Images.Media.getContentUri(volume);
            String[] projection = {
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.RELATIVE_PATH,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_MODIFIED
            };
            String selection = MediaStore.Images.Media.DISPLAY_NAME + "=?";
            List<String> argsList = new ArrayList<>();
            argsList.add(name);
            if (size >= 0) {
                selection += " AND " + MediaStore.Images.Media.SIZE + "=?";
                argsList.add(Long.toString(size));
            }

            List<ImageEntry> out = new ArrayList<>();
            try (Cursor c = resolver.query(collection, projection, selection,
                    argsList.toArray(new String[0]), null)) {
                if (c == null) {
                    return out;
                }
                while (c.moveToNext()) {
                    long id = getLong(c, MediaStore.Images.Media._ID, -1L);
                    if (id < 0) {
                        continue;
                    }
                    String candidateName = getString(c, MediaStore.Images.Media.DISPLAY_NAME);
                    String path = getString(c, MediaStore.Images.Media.RELATIVE_PATH);
                    long candidateSize = getLong(c, MediaStore.Images.Media.SIZE, -1L);
                    long modified = getLong(c, MediaStore.Images.Media.DATE_MODIFIED, -1L);
                    out.add(new ImageEntry(ContentUris.withAppendedId(collection, id), candidateName,
                            volume, path, candidateSize, modified));
                }
            } catch (RuntimeException ignored) {
            }
            return out;
        }

        private static ImageEntry disambiguateCandidate(ContentResolver resolver, Uri opened,
                                                          SourceMetadata source, List<ImageEntry> candidates) {
            if (candidates.size() == 1) {
                return candidates.get(0);
            }

            String openedPath = Uri.decode(opened.getPath() == null ? "" : opened.getPath()).toLowerCase(Locale.ROOT);
            List<ImageEntry> pathMatches = new ArrayList<>();
            for (ImageEntry e : candidates) {
                if (e.relativePath != null) {
                    String normalized = e.relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
                    if (!normalized.isEmpty() && openedPath.contains(normalized.toLowerCase(Locale.ROOT))) {
                        pathMatches.add(e);
                    }
                }
            }
            if (pathMatches.size() == 1) {
                return pathMatches.get(0);
            }
            if (!pathMatches.isEmpty()) {
                candidates = pathMatches;
            }

            byte[] sourceHash = sha256(resolver, opened);
            if (sourceHash != null) {
                List<ImageEntry> hashMatches = new ArrayList<>();
                for (ImageEntry e : candidates) {
                    byte[] h = sha256(resolver, e.uri);
                    if (constantTimeEquals(sourceHash, h)) {
                        hashMatches.add(e);
                    }
                }
                if (hashMatches.size() == 1) {
                    return hashMatches.get(0);
                }
                if (!hashMatches.isEmpty()) {
                    candidates = hashMatches;
                }
            }

            if (source.modifiedMillis >= 0) {
                List<ImageEntry> timeMatches = new ArrayList<>();
                long sourceSeconds = source.modifiedMillis / 1000L;
                for (ImageEntry e : candidates) {
                    if (e.modifiedSeconds >= 0 && Math.abs(e.modifiedSeconds - sourceSeconds) <= 2) {
                        timeMatches.add(e);
                    }
                }
                if (timeMatches.size() == 1) {
                    return timeMatches.get(0);
                }
            }

            // 完全に同一なコピーが複数フォルダへ存在して場所情報もない場合、
            // 誤ったフォルダを開くより現在の1枚だけに留める。
            return null;
        }

        private static int selectOpenedIndex(ContentResolver resolver, Uri opened, SourceMetadata source,
                                             List<ImageEntry> entries, String hintedName, String hintedPath) {
            List<Integer> candidates = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                ImageEntry e = entries.get(i);
                boolean nameMatches = hintedName == null || hintedName.equals(e.name)
                        || (source.name != null && source.name.equals(e.name));
                boolean sizeMatches = source.size < 0 || e.size < 0 || source.size == e.size;
                boolean pathMatches = hintedPath == null || safeEquals(hintedPath, e.relativePath);
                if (nameMatches && sizeMatches && pathMatches) {
                    candidates.add(i);
                }
            }
            if (candidates.size() == 1) {
                return candidates.get(0);
            }
            if (candidates.isEmpty()) {
                return -1;
            }
            byte[] sourceHash = sha256(resolver, opened);
            if (sourceHash != null) {
                int found = -1;
                for (int i : candidates) {
                    if (constantTimeEquals(sourceHash, sha256(resolver, entries.get(i).uri))) {
                        if (found >= 0) {
                            return -1;
                        }
                        found = i;
                    }
                }
                return found;
            }
            return -1;
        }

        private static int indexOfOpenedAfterSort(ContentResolver resolver, Uri opened, SourceMetadata source,
                                                   List<ImageEntry> entries, String name, String path) {
            return selectOpenedIndex(resolver, opened, source, entries, name, path);
        }

        @SuppressWarnings("deprecation")
        private static FolderResult findLegacyApi28(ContentResolver resolver, Uri opened, SourceMetadata source) {
            if (source.name == null) {
                return null;
            }
            Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_MODIFIED
            };
            List<LegacyCandidate> candidates = new ArrayList<>();
            try (Cursor c = resolver.query(collection, projection,
                    MediaStore.Images.Media.DISPLAY_NAME + "=?", new String[]{source.name}, null)) {
                if (c == null) {
                    return null;
                }
                while (c.moveToNext()) {
                    long id = getLong(c, MediaStore.Images.Media._ID, -1L);
                    String data = getString(c, MediaStore.Images.Media.DATA);
                    long size = getLong(c, MediaStore.Images.Media.SIZE, -1L);
                    if (id < 0 || data == null || (source.size >= 0 && size >= 0 && source.size != size)) {
                        continue;
                    }
                    Uri uri = ContentUris.withAppendedId(collection, id);
                    candidates.add(new LegacyCandidate(uri, data, size));
                }
            } catch (RuntimeException ignored) {
                return null;
            }
            if (candidates.isEmpty()) {
                return null;
            }

            LegacyCandidate selected = candidates.size() == 1 ? candidates.get(0) : null;
            if (selected == null) {
                byte[] sourceHash = sha256(resolver, opened);
                for (LegacyCandidate c : candidates) {
                    if (constantTimeEquals(sourceHash, sha256(resolver, c.uri))) {
                        if (selected != null) {
                            return null;
                        }
                        selected = c;
                    }
                }
            }
            if (selected == null) {
                return null;
            }

            File parent = new File(selected.data).getParentFile();
            if (parent == null) {
                return null;
            }
            List<ImageEntry> entries = new ArrayList<>();
            try (Cursor c = resolver.query(collection, projection, null, null, null)) {
                if (c == null) {
                    return null;
                }
                while (c.moveToNext()) {
                    String name = getString(c, MediaStore.Images.Media.DISPLAY_NAME);
                    String data = getString(c, MediaStore.Images.Media.DATA);
                    if (!isSupportedName(name) || data == null) {
                        continue;
                    }
                    File p = new File(data).getParentFile();
                    if (p == null || !parent.equals(p)) {
                        continue;
                    }
                    long id = getLong(c, MediaStore.Images.Media._ID, -1L);
                    if (id >= 0) {
                        entries.add(new ImageEntry(ContentUris.withAppendedId(collection, id), name,
                                null, parent.getAbsolutePath(), getLong(c, MediaStore.Images.Media.SIZE, -1L),
                                getLong(c, MediaStore.Images.Media.DATE_MODIFIED, -1L)));
                    }
                }
            } catch (RuntimeException ignored) {
                return null;
            }
            Collections.sort(entries, NAME_ORDER);
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).uri.equals(selected.uri)) {
                    return new FolderResult(entries, i);
                }
            }
            return null;
        }

        private static SourceMetadata querySourceMetadata(ContentResolver resolver, Uri uri) {
            String name = null;
            long size = -1L;
            long modified = -1L;
            try (Cursor c = resolver.query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    name = getString(c, OpenableColumns.DISPLAY_NAME);
                    size = getLong(c, OpenableColumns.SIZE, -1L);
                }
            } catch (RuntimeException ignored) {
            }
            if (Build.VERSION.SDK_INT >= 19 && DocumentsContract.isDocumentUri(resolver.getContext(), uri)) {
                try (Cursor c = resolver.query(uri,
                        new String[]{DocumentsContract.Document.COLUMN_LAST_MODIFIED},
                        null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        modified = getLong(c, DocumentsContract.Document.COLUMN_LAST_MODIFIED, -1L);
                    }
                } catch (RuntimeException ignored) {
                }
            }
            if (name == null) {
                String segment = uri.getLastPathSegment();
                if (segment != null) {
                    int slash = segment.lastIndexOf('/');
                    name = slash >= 0 ? segment.substring(slash + 1) : segment;
                }
            }
            return new SourceMetadata(name, size, modified);
        }

        private static byte[] sha256(ContentResolver resolver, Uri uri) {
            if (uri == null) {
                return null;
            }
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) {
                    return null;
                }
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
                return digest.digest();
            } catch (Exception ignored) {
                return null;
            }
        }

        private static boolean constantTimeEquals(byte[] a, byte[] b) {
            if (a == null || b == null) {
                return false;
            }
            return MessageDigest.isEqual(a, b);
        }

        private static boolean isSupportedName(String name) {
            if (name == null) {
                return false;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".png") || lower.endsWith(".webp");
        }

        private static String getString(Cursor c, String column) {
            int i = c.getColumnIndex(column);
            return i >= 0 && !c.isNull(i) ? c.getString(i) : null;
        }

        private static long getLong(Cursor c, String column, long fallback) {
            int i = c.getColumnIndex(column);
            return i >= 0 && !c.isNull(i) ? c.getLong(i) : fallback;
        }

        private static boolean safeEquals(Object a, Object b) {
            return a == b || (a != null && a.equals(b));
        }

        private static int naturalCompare(String left, String right) {
            String a = left == null ? "" : left;
            String b = right == null ? "" : right;
            int ai = 0;
            int bi = 0;
            while (ai < a.length() && bi < b.length()) {
                char ac = a.charAt(ai);
                char bc = b.charAt(bi);
                if (Character.isDigit(ac) && Character.isDigit(bc)) {
                    int aStart = ai;
                    int bStart = bi;
                    while (aStart < a.length() && a.charAt(aStart) == '0') aStart++;
                    while (bStart < b.length() && b.charAt(bStart) == '0') bStart++;
                    int aEnd = aStart;
                    int bEnd = bStart;
                    while (aEnd < a.length() && Character.isDigit(a.charAt(aEnd))) aEnd++;
                    while (bEnd < b.length() && Character.isDigit(b.charAt(bEnd))) bEnd++;
                    int aDigits = aEnd - aStart;
                    int bDigits = bEnd - bStart;
                    if (aDigits != bDigits) return aDigits < bDigits ? -1 : 1;
                    for (int i = 0; i < aDigits; i++) {
                        int cmp = Character.compare(a.charAt(aStart + i), b.charAt(bStart + i));
                        if (cmp != 0) return cmp;
                    }
                    int aZeroes = aStart - ai;
                    int bZeroes = bStart - bi;
                    if (aZeroes != bZeroes) return aZeroes < bZeroes ? -1 : 1;
                    ai = aEnd;
                    bi = bEnd;
                    continue;
                }
                int cmp = Character.compare(Character.toLowerCase(ac), Character.toLowerCase(bc));
                if (cmp != 0) return cmp;
                ai++;
                bi++;
            }
            return Integer.compare(a.length() - ai, b.length() - bi);
        }

        private static final class SourceMetadata {
            final String name;
            final long size;
            final long modifiedMillis;

            SourceMetadata(String name, long size, long modifiedMillis) {
                this.name = name;
                this.size = size;
                this.modifiedMillis = modifiedMillis;
            }
        }

        private static final class LegacyCandidate {
            final Uri uri;
            final String data;
            final long size;

            LegacyCandidate(Uri uri, String data, long size) {
                this.uri = uri;
                this.data = data;
                this.size = size;
            }
        }

        private static final class ChromePathHint {
            final String relativeFolder;
            final String fileName;

            ChromePathHint(String relativeFolder, String fileName) {
                this.relativeFolder = relativeFolder;
                this.fileName = fileName;
            }

            static ChromePathHint from(Uri uri) {
                String authority = uri.getAuthority();
                if (!"org.chromium.arc.intent_helper.fileprovider".equals(authority)
                        && !"org.chromium.arc.file_system.fileprovider".equals(authority)) {
                    return null;
                }
                List<String> segments = uri.getPathSegments();
                if (segments == null || segments.size() < 2) {
                    return null;
                }
                String root = segments.get(0);
                String fileName = segments.get(segments.size() - 1);
                if (!isSupportedName(fileName)) {
                    return null;
                }

                StringBuilder folder = new StringBuilder();
                if ("download".equals(root)) {
                    folder.append(Environment.DIRECTORY_DOWNLOADS).append('/');
                } else if ("external_files".equals(root)) {
                    // external_files/<relative Android shared-storage path>/<file>
                } else {
                    return null;
                }

                for (int i = 1; i < segments.size() - 1; i++) {
                    folder.append(segments.get(i)).append('/');
                }
                return new ChromePathHint(folder.toString(), fileName);
            }
        }
    }
}
