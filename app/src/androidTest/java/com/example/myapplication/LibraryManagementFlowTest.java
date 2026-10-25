package com.example.myapplication;

import android.app.Instrumentation;
import android.content.*;
import android.database.Cursor;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import java.io.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class LibraryManagementFlowTest {
    private final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
    private final Context context = inst.getTargetContext();
    private Object call(MainActivity activity, String name, Class<?>[] types, Object... args) {
        try { java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(activity, args); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private Object field(MainActivity activity, String name) {
        try { java.lang.reflect.Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private void set(MainActivity activity, String name, Object value) {
        try { java.lang.reflect.Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(activity, value); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view) && view.isShown()) return type.cast(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            T match = find(((ViewGroup) view).getChildAt(i), type); if (match != null) return match;
        }
        return null;
    }
    private View dialog() {
        for (View root : android.view.inspector.WindowInspector.getGlobalWindowViews())
            if (root.isShown() && (root.findViewById(android.R.id.button1) != null || find(root, ListView.class) != null)) return root;
        throw new AssertionError("Library dialog is missing");
    }
    private void choose(int item) { ListView list = find(dialog(), ListView.class); assertNotNull(list); assertTrue(item < list.getAdapter().getCount()); assertTrue(list.performItemClick(null, item, list.getAdapter().getItemId(item))); }
    private void save(String text) { View root = dialog(); EditText input = find(root, EditText.class); assertNotNull(input); input.setText(text); Button button = root.findViewById(android.R.id.button1); assertNotNull(button); assertTrue(button.performClick()); }
    private long number(String sql, String... args) { try (DocumentStore store = new DocumentStore(context); Cursor row = store.db().rawQuery(sql, args)) { assertTrue(row.moveToFirst()); return row.getLong(0); } }
    private String value(String sql, String... args) { try (DocumentStore store = new DocumentStore(context); Cursor row = store.db().rawQuery(sql, args)) { assertTrue(row.moveToFirst()); return row.getString(0); } }

    private final class Fixture implements AutoCloseable {
        final String prefix = "management-" + UUID.randomUUID();
        final List<File> files = new ArrayList<>();
        long folder(String name, long parent) {
            try (DocumentStore store = new DocumentStore(context)) { ContentValues row = new ContentValues(); row.put("name", prefix + name); row.put("parent", parent); return store.db().insertOrThrow("folders", null, row); }
        }
        long document(String name, long folder, boolean trash) throws Exception {
            PDFBoxResourceLoader.init(context); File directory = new File(context.getFilesDir(), "documents"); assertTrue(directory.isDirectory() || directory.mkdirs());
            File file = File.createTempFile(prefix, ".pdf", directory); files.add(file);
            try (PDDocument document = new PDDocument()) { document.addPage(new PDPage()); document.save(file); }
            try (DocumentStore store = new DocumentStore(context)) {
                store.registerDocument(file.getPath(), prefix + name, 1); ContentValues row = new ContentValues(); row.put("folder_id", folder); row.put("deleted_at", trash ? 1 : 0);
                store.db().update("docs", row, "path=?", new String[]{file.getPath()});
            }
            return number("SELECT id FROM docs WHERE path=?", file.getPath());
        }
        @Override public void close() {
            try (DocumentStore store = new DocumentStore(context)) {
                for (File file : files) { store.db().delete("document_guards", "path=?", new String[]{file.getPath()}); file.delete(); }
                store.db().delete("docs", "name LIKE ?", new String[]{prefix + "%"});
                store.db().delete("folders", "name LIKE ?", new String[]{prefix + "%"});
            }
        }
    }

    @Test public void folderCreationRenameAndEmptyDeletionKeepNamesAndParentsConsistent() throws Exception {
        try (Fixture fixture = new Fixture(); ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            long parent = fixture.folder("-parent", 0);
            java.util.concurrent.atomic.AtomicLong selectedChild = new java.util.concurrent.atomic.AtomicLong();
            scenario.onActivity(a -> {
                set(a, "folderId", parent); call(a, "folderOptions", new Class<?>[]{}); choose(0); save("   ");
            });
            scenario.onActivity(a -> {
                assertEquals(0, number("SELECT COUNT(*) FROM folders WHERE parent=?", Long.toString(parent)));
                call(a, "folderOptions", new Class<?>[]{}); choose(0); save("  " + fixture.prefix + "-child  ");
            });
            scenario.onActivity(a -> {
                long child = number("SELECT id FROM folders WHERE parent=?", Long.toString(parent));
                selectedChild.set(child);
                assertEquals(fixture.prefix + "-child", value("SELECT name FROM folders WHERE id=?", Long.toString(child)));
                call(a, "editFolder", new Class<?>[]{long.class, String.class}, child, fixture.prefix + "-child"); choose(0); save(" ");
            });
            scenario.onActivity(a -> {
                long child = selectedChild.get();
                assertEquals(fixture.prefix + "-child", value("SELECT name FROM folders WHERE id=?", Long.toString(child)));
                call(a, "editFolder", new Class<?>[]{long.class, String.class}, child, fixture.prefix + "-child"); choose(0); save(" " + fixture.prefix + "-renamed ");
            });
            scenario.onActivity(a -> {
                long child = selectedChild.get();
                assertEquals(fixture.prefix + "-parent / " + fixture.prefix + "-renamed", call(a, "folderPath", new Class<?>[]{long.class}, child));
                call(a, "editFolder", new Class<?>[]{long.class, String.class}, child, "renamed"); choose(1);
                assertEquals(0, number("SELECT COUNT(*) FROM folders WHERE id=?", Long.toString(child)));
                call(a, "folderOptions", new Class<?>[]{}); choose(1); assertEquals(0L, field(a, "folderId"));
            });
        }
    }

    @Test public void foldersWithDocumentsSubfoldersOrProtectionReferencesCannotBeDeleted() throws Exception {
        try (Fixture fixture = new Fixture(); ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            long documents = fixture.folder("-documents", 0), children = fixture.folder("-children", 0), guarded = fixture.folder("-guarded", 0);
            fixture.folder("-nested", children); long document = fixture.document("-document", documents, false); File file = fixture.files.get(0);
            try (DocumentStore store = new DocumentStore(context)) { ContentValues row = new ContentValues(); row.put("path", file.getPath()); row.put("folder_id", guarded); store.db().insertOrThrow("document_guards", null, row); }
            scenario.onActivity(a -> {
                for (long folder : new long[]{documents, children, guarded}) {
                    call(a, "editFolder", new Class<?>[]{long.class, String.class}, folder, fixture.prefix); choose(1);
                    assertEquals(1, number("SELECT COUNT(*) FROM folders WHERE id=?", Long.toString(folder)));
                }
                assertEquals(documents, number("SELECT folder_id FROM docs WHERE id=?", Long.toString(document)));
            });
        }
    }

    @Test public void movingASelectionMovesActiveDocumentsAndKeepsTrashAndFilesIntact() throws Exception {
        try (Fixture fixture = new Fixture(); ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            long source = fixture.folder("-source", 0), target = fixture.folder("-target", 0);
            long active = fixture.document("-active", source, false), trashed = fixture.document("-trashed", source, true);
            scenario.onActivity(a -> {
                @SuppressWarnings("unchecked") Map<Long, String> selection = (Map<Long, String>) field(a, "selectedDocuments"); selection.put(active, fixture.files.get(0).getPath());
                call(a, "moveDocumentsTo", new Class<?>[]{List.class, long.class}, Arrays.asList(active, trashed, -1L), target);
                assertTrue(selection.isEmpty()); assertEquals(target, number("SELECT folder_id FROM docs WHERE id=?", Long.toString(active)));
                assertEquals(source, number("SELECT folder_id FROM docs WHERE id=?", Long.toString(trashed)));
                call(a, "moveDocumentsTo", new Class<?>[]{List.class, long.class}, Collections.singletonList(active), 0L);
                assertEquals(0, number("SELECT folder_id FROM docs WHERE id=?", Long.toString(active)));
                for (File file : fixture.files) assertTrue(file.isFile());
            });
        }
    }

    @Test public void restoringTrashAndCancellingThenConfirmingDeletionRespectTheFileAndGuard() throws Exception {
        try (Fixture fixture = new Fixture(); ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            long id = fixture.document("-trash", 0, true); File file = fixture.files.get(0);
            try (DocumentStore store = new DocumentStore(context)) { ContentValues row = new ContentValues(); row.put("path", file.getPath()); row.put("folder_id", 0); store.db().insertOrThrow("document_guards", null, row); }
            scenario.onActivity(a -> {
                call(a, "trashActions", new Class<?>[]{long.class, String.class, String.class}, id, fixture.prefix, file.getPath()); choose(0);
                assertEquals(0, number("SELECT deleted_at FROM docs WHERE id=?", Long.toString(id))); assertTrue(file.isFile());
                try (DocumentStore store = new DocumentStore(context)) { store.trash(id); }
                call(a, "trashActions", new Class<?>[]{long.class, String.class, String.class}, id, fixture.prefix, file.getPath()); choose(1);
                assertTrue(((Button) dialog().findViewById(android.R.id.button2)).performClick());
            });
            scenario.onActivity(a -> {
                assertEquals(1, number("SELECT COUNT(*) FROM docs WHERE id=?", Long.toString(id))); assertTrue(file.isFile());
                call(a, "trashActions", new Class<?>[]{long.class, String.class, String.class}, id, fixture.prefix, file.getPath()); choose(1);
                assertTrue(((Button) dialog().findViewById(android.R.id.button1)).performClick());
            });
            scenario.onActivity(a -> {
                assertEquals(0, number("SELECT COUNT(*) FROM docs WHERE id=?", Long.toString(id))); assertFalse(file.exists());
                assertEquals(0, number("SELECT COUNT(*) FROM document_guards WHERE path=?", file.getPath()));
            });
        }
    }
}
