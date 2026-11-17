/* Copyright  (C) 2010-2018 The RetroArch team
 *
 * ---------------------------------------------------------------------------------------
 * The following license statement only applies to this file (rsemaphore.h).
 * ---------------------------------------------------------------------------------------
 *
 * Permission is hereby granted, free of charge,
 * to any person obtaining a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
 * and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

#ifndef __LIBRETRO_SDK_RSEMAPHORE_H__
#define __LIBRETRO_SDK_RSEMAPHORE_H__

#include <retro_common_api.h>

#include <boolean.h>

RETRO_BEGIN_DECLS

typedef struct ssem ssem_t;

/**
 * ssem_new:
 * @value                  : initial semaphore value
 *
 * Create and initialize a new semaphore. Must be manually
 * freed.
 *
 * Returns: pointer to a new semaphore if successful, otherwise NULL.
 **/
ssem_t *ssem_new(int value);

/**
 * ssem_free:
 * @semaphore              : pointer to semaphore object
 *
 * Frees a semaphore.
 **/
void ssem_free(ssem_t *semaphore);

/**
 * ssem_wait:
 * @semaphore              : pointer to semaphore object
 *
 * Wait on a semaphore (decrement). If the semaphore value
 * is negative, the calling thread will block until the
 * semaphore is signaled.
 **/
void ssem_wait(ssem_t *semaphore);

/**
 * ssem_trywait:
 * @semaphore              : pointer to semaphore object
 *
 * Attempt to wait on a semaphore without blocking.
 * If the semaphore value is zero, returns false immediately.
 *
 * Returns: true if the semaphore was acquired, false otherwise.
 **/
bool ssem_trywait(ssem_t *semaphore);

/**
 * ssem_get:
 * @semaphore              : pointer to semaphore object
 *
 * Get the current value of the semaphore.
 *
 * Returns: the current semaphore value.
 **/
int ssem_get(ssem_t *semaphore);

/**
 * ssem_signal:
 * @semaphore              : pointer to semaphore object
 *
 * Signal a semaphore (increment). If there are threads
 * blocked on this semaphore, one will be unblocked.
 **/
void ssem_signal(ssem_t *semaphore);

RETRO_END_DECLS

#endif