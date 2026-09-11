#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

namespace crisisconnect {

enum class FrameCopyResult {
  kCopied,
  kEmpty,
  kInvalidInput,
  kInsufficientCapacity,
};

inline FrameCopyResult copyFrame(const uint8_t* source,
                                 size_t source_size,
                                 uint8_t* destination,
                                 size_t destination_capacity,
                                 size_t* bytes_written) {
  if (!bytes_written) return FrameCopyResult::kInvalidInput;
  *bytes_written = 0;
  if (source_size == 0) return FrameCopyResult::kEmpty;
  if (source_size > destination_capacity) return FrameCopyResult::kInsufficientCapacity;
  if (!source || !destination) return FrameCopyResult::kInvalidInput;

  std::memcpy(destination, source, source_size);
  *bytes_written = source_size;
  return FrameCopyResult::kCopied;
}

}  // namespace crisisconnect
