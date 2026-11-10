"""Converte os pesos oficiais do Real-ESRGAN (realesr-general-x4v3.pth) para ONNX.

Uso: python export_model.py realesr-general-x4v3.pth saida.onnx
Pesos: https://github.com/xinntao/Real-ESRGAN/releases/tag/v0.2.5.0 (licenca BSD-3-Clause)
"""
import sys
import torch
from torch import nn
from torch.nn import functional as F


class SRVGGNetCompact(nn.Module):
    def __init__(self, num_in_ch=3, num_out_ch=3, num_feat=64, num_conv=32, upscale=4):
        super().__init__()
        self.upscale = upscale
        self.body = nn.ModuleList()
        self.body.append(nn.Conv2d(num_in_ch, num_feat, 3, 1, 1))
        self.body.append(nn.PReLU(num_parameters=num_feat))
        for _ in range(num_conv):
            self.body.append(nn.Conv2d(num_feat, num_feat, 3, 1, 1))
            self.body.append(nn.PReLU(num_parameters=num_feat))
        self.body.append(nn.Conv2d(num_feat, num_out_ch * upscale * upscale, 3, 1, 1))
        self.upsampler = nn.PixelShuffle(upscale)

    def forward(self, x):
        out = x
        for layer in self.body:
            out = layer(out)
        out = self.upsampler(out)
        base = F.interpolate(x, scale_factor=self.upscale, mode="nearest")
        return out + base


def main(src, dst):
    state = torch.load(src, map_location="cpu", weights_only=True)
    state = state.get("params_ema", state.get("params", state))
    model = SRVGGNetCompact()
    model.load_state_dict(state, strict=True)
    model.eval()
    dummy = torch.rand(1, 3, 64, 64)
    torch.onnx.export(
        model, dummy, dst, opset_version=17, dynamo=False,
        input_names=["input"], output_names=["output"],
        dynamic_axes={"input": {2: "h", 3: "w"}, "output": {2: "h4", 3: "w4"}},
    )


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
