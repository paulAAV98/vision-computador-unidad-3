# Reconocimiento de Formas mediante Descriptores de Fourier

Práctica correspondiente a la **Unidad 3 de Visión por Computador**.
El proyecto implementa un sistema de reconocimiento de formas geométricas
(triángulo, cuadrado y círculo) basado en análisis de contornos y descriptores
de Fourier normalizados.

---

## Descripción general
El sistema procesa imágenes dibujadas manualmente en un dispositivo móvil.
A partir de cada imagen se realiza:

1. Segmentación mediante binarización adaptativa.
2. Extracción del contorno más externo.
3. Construcción de una *shape signature* usando coordenadas complejas.
4. Cálculo de la Transformada Rápida de Fourier (FFT).
5. Normalización del descriptor para invariancia a escala y rotación.
6. Clasificación mediante **1-Nearest Neighbor (1-NN)** usando
   **distancia Euclídea**.

---

## Metodología
- **Lenguajes:** C++, Kotlin, XML
- **Librerías:** OpenCV
- **Plataforma:** Android (integración C++ mediante JNI)
- **Descriptor:** Fourier (K = 32 coeficientes)
- **Evaluación:** Leave-One-Out con 30 imágenes dibujadas manualmente

---

## Resultados
- Total de imágenes evaluadas: 30
- Precisión obtenida: **43.3 %**
- La matriz de confusión evidencia confusiones coherentes entre
  figuras geométricamente similares.

---

## Estructura del repositorio
El repositorio contiene únicamente los archivos fuente relevantes para
evaluar la implementación del sistema:
<img width="450" height="654" alt="image" src="https://github.com/user-attachments/assets/6b89b80d-96f6-452e-b116-3f1cdf235682" />



---

## Nota importante
Las librerías nativas (OpenCV `.so`, `libc++_shared.so`) y los archivos de
configuración del proyecto Android no se incluyen, ya que dependen del
entorno local de desarrollo y no son necesarios para evaluar la lógica
del sistema.

---

## Autor
- **Nombre:** Paul Arichabala
- **Carrera:** Ingeniería en Sistemas / Informática
- **Asignatura:** Visión por Computador


