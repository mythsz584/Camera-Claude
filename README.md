# Foto IA

App Android de câmera que melhora as fotos com IA **no próprio celular, sem internet**.
Usa o modelo Real-ESRGAN (`realesr-general-x4v3`) com o ONNX Runtime: reduz ruído,
recupera nitidez e amplia a imagem.

## Como gerar o .apk

1. Envie esta pasta para um repositório no GitHub (branch `main`).
2. A aba **Actions** compila sozinha (leva alguns minutos).
3. Baixe o `FotoIA.apk` na aba **Releases** e instale no celular
   (é preciso permitir "instalar apps de fontes desconhecidas").

Para compilar no computador: abra a pasta no Android Studio, ou rode
`gradle :app:assembleRelease` (Gradle 8.9+, JDK 17).

## Como usar

- Escolha a qualidade (Rápido / Equilibrado / Máximo), tire a foto ou escolha uma da galeria.
- Aguarde a barra de progresso; depois segure a foto para comparar com o "antes".
- **Salvar** grava em `Imagens/FotoIA`.

Quanto maior a qualidade, mais demora (de alguns segundos a mais de um minuto,
dependendo do aparelho). Requer Android 10 ou superior.

## Créditos

Modelo: [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) (licença BSD-3-Clause),
convertido para ONNX com `tools/export_model.py`.
