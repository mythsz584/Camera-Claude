# Foto IA

App Android de câmera que melhora as fotos com IA **no próprio celular, sem internet**.

O modelo (`app/src/main/assets/fotoia-x4.onnx`, rodando com o ONNX Runtime) faz duas coisas:

1. **Super-resolução 4x** com Real-ESRGAN (`realesr-general-x4v3`): reduz ruído e recupera detalhes.
2. **Realce**: níveis, luz (gama), contraste, saturação, clareza e nitidez, calculados para cada foto.

## Recursos

- Flash, grade, temporizador, modo noite, retrato e vídeo.
- Barra de **zoom** à direita (até 100x: zoom da lente + recorte digital) e gesto de pinça.
- Barra de **luz** à esquerda (exposição da câmera); tocar no número volta para zero.
- Toque na imagem para focar.
- Tamanho da foto com IA: Rápido, Equilibrado, Máximo ou Ultra.
- Intensidade do realce: Natural, Forte ou Extremo.
- Depois de processar, segure a foto para comparar com o "antes". Salva em `Imagens/FotoIA`.

Requer Android 10 ou superior. Quanto maior o tamanho escolhido, mais demora.

## Como gerar o .apk

Cada envio para a branch `main` compila sozinho no GitHub (aba **Actions**) e publica
o `FotoIA.apk` na aba **Releases**.

Para compilar no computador: abra a pasta no Android Studio, ou rode
`gradle :app:assembleRelease` (Gradle 8.9+, JDK 17).

## Modelo

`tools/export_model.py` gera o `fotoia-x4.onnx` a partir dos pesos oficiais do
[Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) (licença BSD-3-Clause).
