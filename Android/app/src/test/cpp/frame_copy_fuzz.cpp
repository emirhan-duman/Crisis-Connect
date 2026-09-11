#include "frame_copy.h"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <vector>

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
  if (size == 0) return 0;

  const size_t requested_capacity = static_cast<size_t>(data[0]);
  const uint8_t* source = data + 1;
  const size_t source_size = size - 1;
  std::vector<uint8_t> destination(requested_capacity, 0xA5);
  size_t bytes_written = source_size + 1;

  const auto result = crisisconnect::copyFrame(
      source,
      source_size,
      destination.data(),
      destination.size(),
      &bytes_written);

  if (bytes_written > destination.size()) std::abort();
  if (source_size == 0) {
    if (result != crisisconnect::FrameCopyResult::kEmpty || bytes_written != 0) std::abort();
    return 0;
  }
  if (source_size > destination.size()) {
    if (result != crisisconnect::FrameCopyResult::kInsufficientCapacity || bytes_written != 0) {
      std::abort();
    }
    return 0;
  }

  if (result != crisisconnect::FrameCopyResult::kCopied || bytes_written != source_size) {
    std::abort();
  }
  if (!std::equal(source, source + source_size, destination.begin())) std::abort();
  return 0;
}
