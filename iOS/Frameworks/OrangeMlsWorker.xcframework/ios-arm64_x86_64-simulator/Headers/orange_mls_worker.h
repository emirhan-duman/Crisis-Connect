// C interface of liborange_mls_worker.a (rust-mls-ios). Handshake ops return the wire-format
// JSON string ({broadcast:[...], safetyNumber?:[ints]}); free every returned pointer with the
// matching mls_ios_free_* function.
#ifndef ORANGE_MLS_WORKER_H
#define ORANGE_MLS_WORKER_H

#include <stddef.h>
#include <stdbool.h>

char *mls_ios_new_state(const char *uid);
char *mls_ios_new_state_and_create_group(const char *uid);
char *mls_ios_add_user(const unsigned char *kp, size_t kp_len);
char *mls_ios_remove_user(const char *uid);
char *mls_ios_join_group(const unsigned char *welcome, size_t welcome_len,
                         const unsigned char *rtree, size_t rtree_len);
char *mls_ios_handle_commit(const unsigned char *msg, size_t msg_len, const char *sender_uid);

unsigned char *mls_ios_encrypt_frame(const unsigned char *input, size_t input_len, size_t *out_len);
unsigned char *mls_ios_decrypt_frame(const unsigned char *input, size_t input_len, size_t *out_len);
unsigned char *mls_ios_export_state(size_t *out_len);
bool mls_ios_import_state(const unsigned char *input, size_t input_len);
unsigned char *mls_ios_encrypt_application(const unsigned char *input, size_t input_len, size_t *out_len);
unsigned char *mls_ios_decrypt_application(const unsigned char *input, size_t input_len, size_t *out_len);

char *mls_ios_persistent_new_state(const char *context_id, const char *uid);
char *mls_ios_persistent_new_state_and_create_group(const char *context_id, const char *uid);
char *mls_ios_persistent_add_user(const char *context_id,
                                  const unsigned char *kp, size_t kp_len,
                                  const char *expected_credential,
                                  const unsigned char *expected_signing_key,
                                  size_t expected_signing_key_len);
char *mls_ios_persistent_identity(const char *context_id);
char *mls_ios_persistent_roster(const char *context_id);
unsigned char *mls_ios_persistent_safety_number(const char *context_id, size_t *out_len);
char *mls_ios_persistent_remove_user(const char *context_id, const char *uid);
char *mls_ios_persistent_join_group(const char *context_id,
                                    const unsigned char *welcome, size_t welcome_len,
                                    const unsigned char *rtree, size_t rtree_len);
char *mls_ios_persistent_handle_commit(const char *context_id,
                                       const unsigned char *msg, size_t msg_len,
                                       const char *sender_uid);
unsigned char *mls_ios_persistent_export_state(const char *context_id, size_t *out_len);
bool mls_ios_persistent_import_state(const char *context_id,
                                     const unsigned char *input, size_t input_len);
unsigned char *mls_ios_persistent_encrypt_application(const char *context_id,
                                                       const unsigned char *input, size_t input_len,
                                                       size_t *out_len);
unsigned char *mls_ios_persistent_decrypt_application(const char *context_id,
                                                       const unsigned char *input, size_t input_len,
                                                       size_t *out_len);
bool mls_ios_persistent_close(const char *context_id);

void mls_ios_free_string(char *ptr);
void mls_ios_free_bytes(unsigned char *ptr, size_t len);

#endif
