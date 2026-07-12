#ifndef VOICE_INBOX_NATIVE_TRANSCRIPTION_H
#define VOICE_INBOX_NATIVE_TRANSCRIPTION_H

#include <stdbool.h>
#include <stddef.h>

bool voiceinbox_transcription_initialize(const char *model_directory);
char *voiceinbox_transcription_transcribe_chunk_json(const float *samples, size_t sample_count);
char *voiceinbox_transcription_last_error(void);
void voiceinbox_transcription_string_free(char *value);

#endif
