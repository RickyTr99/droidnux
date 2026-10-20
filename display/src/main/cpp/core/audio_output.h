/*
 * Audio output service for the container: plays what DroidNux's PipeWire
 * module (guest/pipewire) writes into a shared ring, with AAudio. Protocol in
 * audio_protocol.h.
 */
#ifndef DROIDNUX_AUDIO_OUTPUT_H
#define DROIDNUX_AUDIO_OUTPUT_H

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Listens on `socket_path` (replacing a stale socket) on a thread of its own. */
bool dnx_audio_output_start(const char* socket_path);

#ifdef __cplusplus
}
#endif

#endif /* DROIDNUX_AUDIO_OUTPUT_H */
