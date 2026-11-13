/*
 * Camera service for the container: the device's cameras (Camera2) as
 * frames in a shared ring, for DroidNux's PipeWire module
 * (guest/pipewire/camera.c) to show as camera nodes. Protocol in
 * camera_protocol.h.
 */
#ifndef DROIDNUX_CAMERA_INPUT_H
#define DROIDNUX_CAMERA_INPUT_H

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Listens on `socket_path` (replacing a stale socket) on a thread of its own. */
bool dnx_camera_input_start(const char* socket_path);

/* The screen's rotation from its natural orientation (Display.getRotation()
 * in degrees): frames are turned upright for it. */
void dnx_camera_input_set_rotation(int degrees);

/* Called, on a camera thread, when a camera cannot open for lack of Android's
 * permission: the app asks the user, then calls dnx_camera_input_set_permission. */
typedef void (*dnx_camera_permission_callback)(void);
void dnx_camera_input_set_permission_callback(dnx_camera_permission_callback callback);

/* The user's answer: cameras waiting for it open, or tell their client no. */
void dnx_camera_input_set_permission(bool granted);

/* The session is paused (its processes stopped): every camera closes, and the
 * ones in use open again when it continues. */
void dnx_camera_input_set_paused(bool paused);

#ifdef __cplusplus
}
#endif

#endif /* DROIDNUX_CAMERA_INPUT_H */
