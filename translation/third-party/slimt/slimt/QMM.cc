#include "slimt/QMM.hh"

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "slimt/Tensor.hh"

// PlayTranslate spike patch: select exactly ONE int8 provider, preferring
// gemmology. On Android we keep ruy linked for float sgemm (TensorOps.cc, the
// WITH_BLAS=OFF path) while using gemmology for int8, whose u8 x i8 "shift"
// arithmetic matches the intgemm path Firefox's engine runs.
// (DavidVentura/offline-translator#185, once read as a ruy-on-ARM int8 bug,
// was closed there as near-tie decoding noise, not a ruy defect.)
// Original slimt declared the three blocks with independent #ifdefs, which
// double-defines kProvider when ruy+gemmology are both enabled.
#if defined(SLIMT_HAS_GEMMOLOGY)
namespace slimt::qmm::detail {
constexpr Provider kProvider = Provider::Gemmology;
}
// NOLINTNEXTLINE: The C++ file inclusion is intended.
#include "slimt/qmm/Gemmology.inl.cc"
#elif defined(SLIMT_HAS_RUY)
namespace slimt::qmm::detail {
constexpr Provider kProvider = Provider::Ruy;
}
// NOLINTNEXTLINE: The C++ file inclusion is intended.
#include "slimt/qmm/Ruy.inl.cc"
#elif defined(SLIMT_HAS_INTGEMM)
namespace slimt::qmm::detail {
constexpr Provider kProvider = Provider::Intgemm;
}
// NOLINTNEXTLINE: The C++ file inclusion is intended.
#include "slimt/qmm/Intgemm.inl.cc"
#endif

namespace slimt::qmm {
Tensor affine(const Tensor& x, const Tensor& W, const Tensor& b, float a_quant,
              float b_quant, const std::string& name) {
  using detail::affine;
  using detail::kProvider;
  return affine<kProvider>(x, W, b, a_quant, b_quant, name);
}

Tensor affine_with_select(const Tensor& x, const Tensor& W, const Tensor& b,
                          float a_quant, float b_quant,
                          const std::vector<uint32_t>& indices,
                          const std::string& name) {
  using detail::affine_with_select;
  using detail::kProvider;
  return affine_with_select<kProvider>(x, W, b, a_quant, b_quant, indices,
                                       name);
}

Tensor dot(const Tensor& x, const Tensor& W, float a_quant, float b_quant,
           const std::string& name) {
  using detail::dot;
  using detail::kProvider;
  return dot<kProvider>(x, W, a_quant, b_quant, name);
}

void prepare_weight_transposed(const float* weights, int8_t* prepared,
                               float quantization_multiplier, size_t cols,
                               size_t rows) {
  using detail::kProvider;
  using detail::prepare_weight_transposed;
  prepare_weight_transposed<kProvider>(weights, prepared,
                                       quantization_multiplier, cols, rows);
}

void prepare_weight_quantized_transposed(const int8_t* input, int8_t* output,
                                         size_t rows, size_t cols) {
  using detail::kProvider;
  using detail::prepare_weight_quantized_transposed;
  prepare_weight_quantized_transposed<kProvider>(input, output, rows, cols);
}

}  // namespace slimt::qmm
