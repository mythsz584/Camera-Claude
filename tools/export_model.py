"""Gera o modelo usado pelo app: super-resolucao 4x (Real-ESRGAN) + realce da foto.

Uso: python export_model.py realesr-general-x4v3.pth fotoia-x4.onnx
Pesos: https://github.com/xinntao/Real-ESRGAN/releases/tag/v0.2.5.0 (licenca BSD-3-Clause)

Entradas do modelo:
  input  [1, 3, H, W]  imagem RGB de 0 a 1
  params [7]           preto, ganho, gama, contraste, saturacao, nitidez, clareza
Saida:
  output [1, 3, 4H, 4W] imagem RGB de 0 a 1
"""
import math
import sys

import torch
from torch import nn
from torch.nn import functional as F

SHARP_SIGMA, SHARP_RADIUS = 2.0, 6
CLARITY_SIGMA, CLARITY_RADIUS = 10.0, 24


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


def gauss(sigma, radius):
    xs = torch.arange(-radius, radius + 1, dtype=torch.float32)
    k = torch.exp(-(xs * xs) / (2 * sigma * sigma))
    return k / k.sum()


class FotoIA(nn.Module):
    """Super-resolucao seguida de niveis, gama, contraste, saturacao, clareza e nitidez."""

    def __init__(self, sr):
        super().__init__()
        self.sr = sr
        self.register_buffer("luma_w", torch.tensor([0.299, 0.587, 0.114]).view(1, 3, 1, 1))
        self.register_buffer("k_sharp", gauss(SHARP_SIGMA, SHARP_RADIUS))
        self.register_buffer("k_clarity", gauss(CLARITY_SIGMA, CLARITY_RADIUS))

    @staticmethod
    def blur(x, k, r):
        x = F.pad(x, (r, r, r, r), mode="replicate")
        x = F.conv2d(x, k.view(1, 1, 1, -1))
        x = F.conv2d(x, k.view(1, 1, -1, 1))
        return x

    def forward(self, x, p):
        black, gain, gamma, contrast, sat, sharp, clarity = (p[i] for i in range(7))
        y = self.sr(x).clamp(0.0, 1.0)
        # niveis e gama (luz)
        y = ((y - black) * gain).clamp(0.0, 1.0)
        y = torch.pow(y, gamma)
        # contraste em S
        s = y * y * (3.0 - 2.0 * y)
        y = y + contrast * (s - y)
        # saturacao
        # (vibracao: cores ja fortes sao menos reforcadas, e nenhum canal passa de 0..1,
        #  para a cor nao estourar nem mudar de tom)
        luma = (y * self.luma_w).sum(dim=1, keepdim=True)
        mx = y.max(dim=1, keepdim=True).values
        mn = y.min(dim=1, keepdim=True).values
        f = 1.0 + (sat - 1.0) * (1.0 - (mx - mn))
        f = torch.minimum(f, (1.0 - luma) / (mx - luma + 1e-4))
        f = torch.minimum(f, luma / (luma - mn + 1e-4))
        y = luma + f * (y - luma)
        # nitidez (detalhe fino) e clareza (contraste local), limitados para nao criar halos
        fine = (luma - self.blur(luma, self.k_sharp, SHARP_RADIUS)).clamp(-0.10, 0.10)
        local = (luma - self.blur(luma, self.k_clarity, CLARITY_RADIUS)).clamp(-0.15, 0.15)
        y = y + sharp * fine + clarity * local
        return y.clamp(0.0, 1.0)


def main(src, dst):
    state = torch.load(src, map_location="cpu", weights_only=True)
    state = state.get("params_ema", state.get("params", state))
    sr = SRVGGNetCompact()
    sr.load_state_dict(state, strict=True)
    model = FotoIA(sr).eval()
    dummy = torch.rand(1, 3, 64, 64)
    params = torch.tensor([0.0, 1.0, 1.0, 0.0, 1.0, 0.0, 0.0])
    torch.onnx.export(
        model, (dummy, params), dst, opset_version=17, dynamo=False,
        input_names=["input", "params"], output_names=["output"],
        dynamic_axes={"input": {2: "h", 3: "w"}, "output": {2: "h4", 3: "w4"}},
    )


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
