#pragma once
#include <cstddef>
#include <optional>
#include <string>
#include <tuple>
#include <vector>

#include "slimt/Io.hh"
#include "slimt/Modules.hh"
#include "slimt/Tensor.hh"
#include "slimt/Types.hh"

namespace slimt {

class Encoder {
 public:
  explicit Encoder(size_t layers, size_t num_heads, size_t feed_forward_depth);
  Tensor forward(const Tensor &embedding, const Tensor &mask) const;
  void register_parameters(const std::string &prefix, ParameterMap &parameters);
  const std::vector<EncoderLayer> &encoder() const { return encoder_; }

 private:
  std::vector<EncoderLayer> encoder_;
};

class Decoder {
 public:
  Decoder(size_t layers, size_t num_heads, size_t feed_forward_depth,
          const Tensor &embedding);

  void register_parameters(const std::string &prefix, ParameterMap &parameters);

  // Re-point the decoder's (target-side) embedding after parameter load, and
  // tell it whether the model is split-vocab. Tied models keep the shared
  // source embedding and the "Wemb_intgemm8"/"none_QuantMultA" output params;
  // split-vocab models use the separate target embedding and the
  // "decoder_Wemb_*" params. See Transformer's constructor.
  void set_embedding(const Tensor &embedding) { embedding_ = &embedding; }
  void set_split_vocab(bool split) { split_vocab_ = split; }

  std::vector<Tensor> start_states(size_t batch_size) const;
  // One greedy decoding step. `position` is the target position of this step
  // (0 for the first, which reads the empty start embedding); the decoder
  // input gets that position's sinusoidal signal, as Marian's
  // DecoderTransformer::step adds `startPos`.
  std::tuple<Tensor, Tensor> step(const Tensor &encoder_out, const Tensor &mask,
                                  std::vector<Tensor> &states,
                                  const Words &previous_step,
                                  const std::optional<Words> &shortlist,
                                  size_t position) const;

 private:
  const Tensor *embedding_;
  bool split_vocab_ = false;
  std::vector<DecoderLayer> decoder_;
  Affine output_;
};

class Vocabulary;

Words greedy_sample(const Tensor &logits, const Vocabulary &vocabulary,
                    size_t batch_size);
Words greedy_sample_from_words(const Tensor &logits,
                               const Vocabulary &vocabulary, const Words &words,
                               size_t batch_size);

// Scales by sqrt(embed_dim) and adds the sinusoidal signal for positions
// [start, start + sequence_length). No default: a decoder step must pass its
// own position, and a silent start of 0 there made every step read position 0
// (greedy decoding then repeats phrases).
void transform_embedding(Tensor &word_embedding, size_t start);

class Transformer {
 public:
  explicit Transformer(size_t encoder_layers, size_t decoder_layers,
                       size_t num_heads, size_t feed_forward_depth, View model);

  const Tensor &embedding() const { return embedding_; }
  const Encoder &encoder() const { return encoder_; }
  const Decoder &decoder() const { return decoder_; }

 private:
  void register_parameters(const std::string &prefix, ParameterMap &parameters);
  void load_parameters();

  std::vector<io::Item> items_;
  Tensor embedding_;          // encoder/source (or tied) float embedding
  Tensor decoder_embedding_;  // decoder/target float embedding (split-vocab only)
  bool split_vocab_ = false;
  Encoder encoder_;
  Decoder decoder_;
};

}  // namespace slimt
