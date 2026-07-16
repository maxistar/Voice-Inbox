package me.maxistar.voiceinbox;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

public class TestAudioDocumentsProvider extends ContentProvider {
    public static final String AUTHORITY = "me.maxistar.voiceinbox.test.documents";
    public static final String ROOT_ID = "root";

    private static final String WAV_ID = "wav";
    private static final String M4A_ID = "m4a";
    private static final String OGG_ID = "ogg";
    private static final String OPUS_ID = "opus";
    private static final String HIDDEN_AUDIO_ID = "hidden";
    private static final String TEXT_ID = "text";
    private static final String OUTPUT_ID = "output";
    private static final String NESTED_ID = "nested";
    private static final String NESTED_AUDIO_ID = "nested/audio";

    private static final String[] DOCUMENT_COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS,
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(
        Uri uri,
        String[] projection,
        String selection,
        String[] selectionArgs,
        String sortOrder
    ) {
        String parentDocumentId = documentIdBefore(uri, "children");
        if (parentDocumentId != null) return queryChildDocuments(parentDocumentId, projection);

        String documentId = documentIdAfter(uri, "document");
        if (documentId != null) return queryDocument(documentId, projection);

        return new MatrixCursor(columns(projection));
    }

    private Cursor queryDocument(String documentId, String[] projection) {
        MatrixCursor cursor = new MatrixCursor(columns(projection));
        if (ROOT_ID.equals(documentId)) {
            addDocument(cursor, ROOT_ID, "Test audio folder", DocumentsContract.Document.MIME_TYPE_DIR, null, null);
        } else if (WAV_ID.equals(documentId)) {
            addDocument(cursor, WAV_ID, "recording.wav", "audio/wav", 100L, 10L);
        } else if (M4A_ID.equals(documentId)) {
            addDocument(cursor, M4A_ID, "recording.m4a", "audio/mp4", 200L, 20L);
        } else if (OGG_ID.equals(documentId)) {
            addDocument(cursor, OGG_ID, "voice.ogg", "audio/ogg", 210L, 21L);
        } else if (OPUS_ID.equals(documentId)) {
            addDocument(cursor, OPUS_ID, "voice.opus", "audio/opus", 220L, 22L);
        } else if (TEXT_ID.equals(documentId)) {
            addDocument(cursor, TEXT_ID, "notes.txt", "text/plain", 20L, 30L);
        } else if (OUTPUT_ID.equals(documentId)) {
            addDocument(cursor, OUTPUT_ID, "transcripts.txt", "text/plain", 0L, 31L);
        } else if (NESTED_ID.equals(documentId)) {
            addDocument(cursor, NESTED_ID, "nested", DocumentsContract.Document.MIME_TYPE_DIR, null, null);
        } else if (NESTED_AUDIO_ID.equals(documentId)) {
            addDocument(cursor, NESTED_AUDIO_ID, "nested.wav", "audio/wav", 300L, 40L);
        }
        return cursor;
    }

    private Cursor queryChildDocuments(String parentDocumentId, String[] projection) {
        MatrixCursor cursor = new MatrixCursor(columns(projection));
        if (ROOT_ID.equals(parentDocumentId)) {
            addDocument(cursor, WAV_ID, "recording.wav", "audio/wav", 100L, 10L);
            addDocument(cursor, M4A_ID, "recording.m4a", "audio/mp4", 200L, 20L);
            addDocument(cursor, HIDDEN_AUDIO_ID, ".hidden.wav", "audio/wav", 400L, 50L);
            addDocument(cursor, TEXT_ID, "notes.txt", "text/plain", 20L, 30L);
            addDocument(cursor, NESTED_ID, "nested", DocumentsContract.Document.MIME_TYPE_DIR, null, null);
        } else if (NESTED_ID.equals(parentDocumentId)) {
            addDocument(cursor, NESTED_AUDIO_ID, "nested.wav", "audio/wav", 300L, 40L);
        }
        return cursor;
    }

    private static String[] columns(String[] projection) {
        return projection == null ? DOCUMENT_COLUMNS : projection;
    }

    private static String documentIdBefore(Uri uri, String marker) {
        int index = uri.getPathSegments().indexOf(marker);
        if (index <= 0) return null;
        return uri.getPathSegments().get(index - 1);
    }

    private static String documentIdAfter(Uri uri, String marker) {
        int index = uri.getPathSegments().indexOf(marker);
        if (index < 0 || index + 1 >= uri.getPathSegments().size()) return null;
        return uri.getPathSegments().get(index + 1);
    }

    private static void addDocument(
        MatrixCursor cursor,
        String id,
        String name,
        String mime,
        Long size,
        Long modified
    ) {
        cursor.newRow()
            .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
            .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
            .add(DocumentsContract.Document.COLUMN_MIME_TYPE, mime)
            .add(DocumentsContract.Document.COLUMN_SIZE, size)
            .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, modified)
            .add(DocumentsContract.Document.COLUMN_FLAGS, 0);
    }

    @Override
    public String getType(Uri uri) {
        String documentId = documentIdAfter(uri, "document");
        if (WAV_ID.equals(documentId) || NESTED_AUDIO_ID.equals(documentId)) return "audio/wav";
        if (M4A_ID.equals(documentId)) return "audio/mp4";
        if (OGG_ID.equals(documentId)) return "audio/ogg";
        if (OPUS_ID.equals(documentId)) return "audio/opus";
        if (TEXT_ID.equals(documentId) || OUTPUT_ID.equals(documentId)) return "text/plain";
        return null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String documentId = documentIdAfter(uri, "document");
        if (documentId == null || ROOT_ID.equals(documentId) || NESTED_ID.equals(documentId)) {
            throw new FileNotFoundException(uri.toString());
        }
        File file = new File(getContext().getCacheDir(), "shared-" + documentId.replace('/', '-') + ".bin");
        if (OUTPUT_ID.equals(documentId)) {
            return ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_READ_WRITE
            );
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(("test-audio-" + documentId).getBytes());
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
