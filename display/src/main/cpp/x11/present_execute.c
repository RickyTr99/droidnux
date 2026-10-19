/*
 * Copyright © 2013 Keith Packard
 *
 * Permission to use, copy, modify, distribute, and sell this software and its
 * documentation for any purpose is hereby granted without fee, provided that
 * the above copyright notice appear in all copies and that both that copyright
 * notice and this permission notice appear in supporting documentation, and
 * that the name of the copyright holders not be used in advertising or
 * publicity pertaining to distribution of the software without specific,
 * written prior permission.  The copyright holders make no representations
 * about the suitability of this software for any purpose.  It is provided "as
 * is" without express or implied warranty.
 *
 * THE COPYRIGHT HOLDERS DISCLAIM ALL WARRANTIES WITH REGARD TO THIS SOFTWARE,
 * INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS, IN NO
 * EVENT SHALL THE COPYRIGHT HOLDERS BE LIABLE FOR ANY SPECIAL, INDIRECT OR
 * CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE,
 * DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER
 * TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE
 * OF THIS SOFTWARE.
 */

/*
 * DroidNux: xserver's present/present_execute.c with the copy handed to the
 * DDX first (dnx_present_copy in output.c). Defining every symbol of that
 * file here keeps the linker from taking it out of libxserver_present.a, so
 * this needs no rebuild of the prebuilt server libraries. Termux:X11's GPU
 * copy hooks (lorieTryScheduleGpuCopy and the polling for its completion)
 * are gone: our GPU copies are synchronous.
 */

#include <dix-config.h>

#include "present_priv.h"
#include "private.h"

/*
 * Called when the wait fence is triggered; just gets the current msc/ust and
 * calls the proper execute again. That will re-check the fence and pend the
 * request again if it's still not actually ready
 */
static void
present_wait_fence_triggered(void *param)
{
    present_vblank_ptr      vblank = param;
    ScreenPtr               screen = vblank->screen;
    present_screen_priv_ptr screen_priv = present_screen_priv(screen);
    /* lorie: disarm, or miSyncTriggerFence()'s restart-scan re-fires this callback. */
    present_fence_set_callback(vblank->wait_fence, NULL, NULL);

    screen_priv->re_execute(vblank);
}

Bool
present_execute_wait(present_vblank_ptr vblank, uint64_t crtc_msc)
{
    WindowPtr                   window = vblank->window;
    ScreenPtr                   screen = window->drawable.pScreen;
    present_screen_priv_ptr screen_priv = present_screen_priv(screen);

    /* We may have to requeue for the next MSC if check_flip_window prevented
     * using a flip.
     */
    if (vblank->exec_msc == crtc_msc + 1 &&
        screen_priv->queue_vblank(screen, window, vblank->crtc, vblank->event_id,
                                  vblank->exec_msc) == Success)
        return TRUE;

    if (vblank->wait_fence) {
        if (!present_fence_check_triggered(vblank->wait_fence)) {
            present_fence_set_callback(vblank->wait_fence, present_wait_fence_triggered, vblank);
            return TRUE;
        }
    }
    return FALSE;
}

void
present_execute_copy(present_vblank_ptr vblank, uint64_t crtc_msc)
{
    WindowPtr                   window = vblank->window;
    ScreenPtr                   screen = window->drawable.pScreen;
    present_screen_priv_ptr screen_priv = present_screen_priv(screen);
    enum dnx_present_copy       copy;

    /* If present_flip failed, we may have to requeue for the next MSC */
    if (vblank->exec_msc == crtc_msc + 1 &&
        Success == screen_priv->queue_vblank(screen,
                                             window,
                                             vblank->crtc,
                                             vblank->event_id,
                                             vblank->exec_msc)) {
        vblank->queued = TRUE;
        return;
    }

    copy = dnx_present_copy(vblank);
    if (copy == DNX_PRESENT_COPY_CPU) {
        present_copy_region(&window->drawable, vblank->pixmap, vblank->update, vblank->x_off, vblank->y_off);

        /* present_copy_region sticks the region into a scratch GC,
         * which is then freed, freeing the region
         */
        vblank->update = NULL;
    }

    screen_priv->flush(window);

    /* A buffer on its own layer becomes idle once the display lets go of it. */
    if (copy != DNX_PRESENT_COPY_LAYER)
        present_pixmap_idle(vblank->pixmap, vblank->window, vblank->serial, vblank->idle_fence);
}

void
present_execute_post(present_vblank_ptr vblank, uint64_t ust, uint64_t crtc_msc)
{
    uint8_t mode;

    /* Compute correct CompleteMode
     */
    if (vblank->kind == PresentCompleteKindPixmap) {
        if (vblank->pixmap && vblank->window) {
            if (vblank->has_suboptimal && vblank->reason == PRESENT_FLIP_REASON_BUFFER_FORMAT)
                mode = PresentCompleteModeSuboptimalCopy;
            else
                mode = PresentCompleteModeCopy;
        } else {
            mode = PresentCompleteModeSkip;
        }
    }
    else
        mode = PresentCompleteModeCopy;

    present_vblank_notify(vblank, vblank->kind, mode, ust, crtc_msc);
    present_vblank_destroy(vblank);
}
