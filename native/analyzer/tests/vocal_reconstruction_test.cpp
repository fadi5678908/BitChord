#include "../vocal_spectrogram.h"
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
using namespace bitchord::smart;
int main() {
  for (size_t n : {4097, 10001, 44100}) {
    std::vector<std::vector<float>> pcm(2, std::vector<float>(n));
    for (size_t i=0;i<n;++i) { pcm[0][i]=.3f*std::sin(i*.071); pcm[1][i]=.2f*std::cos(i*.103); }
    auto spec=ComputeVocalSpectrogram(pcm,44100);
    for (float gain : {0.f,.25f,1.f}) {
      auto result=ReconstructVocal(pcm,std::vector<float>(spec.values.size(),gain),spec.frames);
      assert(result.size()==n*2);
      for (size_t i=0;i<n;++i) for (size_t c=0;c<2;++c) {
        assert(std::abs(result[2*i+c]-pcm[c][i]*gain)<1e-5);
        auto residual=pcm[c][i]-result[2*i+c];
        assert(std::abs(result[2*i+c]+residual-pcm[c][i])<1e-6);
      }
    }
    auto mask=std::vector<float>(spec.values.size(),std::numeric_limits<float>::quiet_NaN());
    auto clean=ReconstructVocal(pcm,mask,spec.frames);
    for(auto sample:clean) assert(std::isfinite(sample)&&std::abs(sample)<1e-7);
    assert(ReconstructVocal(pcm,{},spec.frames).empty());
  }
  std::cout<<"Vocal reconstruction: identity, silence, fractional masks, residual sum, stereo, boundaries and NaN passed\n";
}
