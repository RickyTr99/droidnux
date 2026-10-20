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

/* Called, on an audio thread, when the first sink starts playing (true) and
 * when the last one stops (false): the app holds Android's audio focus in
 * between. */
typedef void (*dnx_audio_playing_callback)(bool playing);
void dnx_audio_output_set_playing_callback(dnx_audio_playing_callback callback);

/* Silences the output (the ring keeps flowing) while another app has the focus. */
void dnx_audio_output_set_muted(bool muted);

#ifdef __cplusplus
}
#endif

#endif /* DROIDNUX_AUDIO_OUTPUT_H */
