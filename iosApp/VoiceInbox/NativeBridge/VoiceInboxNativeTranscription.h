#ifndef VOICE_INBOX_NATIVE_TRANSCRIPTION_H
#define VOICE_INBOX_NATIVE_TRANSCRIPTION_H

#include <stdbool.h>
#include <stddef.h>

bool voiceinbox_transcription_initialize(const char *model_directory);
bool voiceinbox_transcription_initialize_configured(
    const char *backend,
    const char *installation_identity,
    const char *model_directory,
    const char *primary_file
);
char *voiceinbox_transcription_transcribe_chunk_json(const float *samples, size_t sample_count);
char *voiceinbox_transcription_last_error(void);
void voiceinbox_transcription_string_free(char *value);

#endif
