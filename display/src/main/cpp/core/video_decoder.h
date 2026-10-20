/*
 * Video decoder service for the container: decodes with the device's
 * MediaCodec decoders and hands every frame to the client as a dma-buf, so
 * DroidNux's VA-API driver (guest/va) gives Linux apps hardware decoding.
 * Protocol in video_protocol.h.
 */
#ifndef DROIDNUX_VIDEO_DECODER_H
#define DROIDNUX_VIDEO_DECODER_H

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Listens on `socket_path` (replacing a stale socket) on a thread of its own. */
bool dnx_video_decoder_start(const char* socket_path);

#ifdef __cplusplus
}
#endif

#endif /* DROIDNUX_VIDEO_DECODER_H */
