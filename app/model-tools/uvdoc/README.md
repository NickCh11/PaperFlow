# UVDoc model conversion

Upstream: https://github.com/tanguymagne/UVDoc at commit
`4c9b82b537057aff2526e6dd118a847cdd072e82` (MIT; see UVDoc-LICENSE).
`model.py` is the upstream architecture. Obtain upstream `model/best_model.pkl`
and save it beside the export script as `uvdoc-model.pkl`.

Checkpoint SHA-256: `7e90861b8a516eb4bc51f84bd889cb77275743d2d1d3ca8091951ec9f2b7da23`.
Conversion used Python 3.11, torch 2.6.0 CPU and onnx 1.17.0:

```
python export-uvdoc.py
```

The export retains only the 2D backward grid, uses opset 11, RGB float32 / 255,
and fixed NCHW input 1 x 3 x 712 x 488. Copy the resulting uvdoc.onnx into
app/src/main/assets/models. Expected SHA-256:
`41796fa7aef61d96b7e053d652d0e9e5992f4134ed11daa3bbd3179db5069200`.
The mobile integration upsamples with align_corners=true and remaps the source.
Review the preview: learned dewarping can move or crop edge content.
