// C interface of liborange_mls_worker.a (rust-mls-ios). Handshake ops return the wire-format
// JSON string ({broadcast:[...], safetyNumber?:[ints]}); free every returned pointer with the
// matching mls_ios_free_* function.
#ifndef ORANGE_MLS_WORKER_H
#define ORANGE_MLS_WORKER_H

#include <stddef.h>

char *mls_ios_new_state(const char *uid);
char *mls_ios_new_state_and_create_group(const char *uid);
char *mls_ios_add_user(const unsigned char *kp, size_t kp_len);
char *mls_ios_remove_user(const char *uid);
char *mls_ios_join_group(const unsigned char *welcome, size_t welcome_len,
                         const unsigned char *rtree, size_t rtree_len);
char *mls_ios_handle_commit(const unsigned char *msg, size_t msg_len, const char *sender_uid);

unsigned char *mls_ios_encrypt_frame(const unsigned char *input, size_t input_len, size_t *out_len);
unsigned char *mls_ios_decrypt_frame(const unsigned char *input, size_t input_len, size_t *out_len);

void mls_ios_free_string(char *ptr);
void mls_ios_free_bytes(unsigned char *ptr, size_t len);

#endif
