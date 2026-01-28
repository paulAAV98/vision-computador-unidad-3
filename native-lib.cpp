#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <cstdint>

#include <opencv2/imgproc.hpp>
#include <opencv2/core.hpp>

static cv::Mat argbToRgbaMat(JNIEnv* env, jintArray argbPixels, int w, int h) {
    jint* inPtr = env->GetIntArrayElements(argbPixels, nullptr);

    cv::Mat rgba(h, w, CV_8UC4);
    for (int i = 0; i < w * h; i++) {
        uint32_t p = (uint32_t)inPtr[i];
        uint8_t a = (p >> 24) & 0xFF;
        uint8_t r = (p >> 16) & 0xFF;
        uint8_t g = (p >> 8)  & 0xFF;
        uint8_t b = (p)       & 0xFF;

        rgba.data[i * 4 + 0] = r;
        rgba.data[i * 4 + 1] = g;
        rgba.data[i * 4 + 2] = b;
        rgba.data[i * 4 + 3] = a;
    }

    env->ReleaseIntArrayElements(argbPixels, inPtr, JNI_ABORT);
    return rgba;
}

static jintArray rgbaMatToArgb(JNIEnv* env, const cv::Mat& rgba) {
    const int w = rgba.cols;
    const int h = rgba.rows;

    jintArray result = env->NewIntArray(w * h);
    std::vector<jint> outPixels(w * h);

    for (int i = 0; i < w * h; i++) {
        uint8_t r = rgba.data[i * 4 + 0];
        uint8_t g = rgba.data[i * 4 + 1];
        uint8_t b = rgba.data[i * 4 + 2];
        uint8_t a = rgba.data[i * 4 + 3];
        outPixels[i] = ((jint)a << 24) | ((jint)r << 16) | ((jint)g << 8) | (jint)b;
    }

    env->SetIntArrayRegion(result, 0, w * h, outPixels.data());
    return result;
}

// Pipeline robusta para UPS: suaviza + Otsu + (si hace falta) invertir + morfología ligera
static bool buildBinaryAndBestContour(
        const cv::Mat& rgba,
        cv::Mat& bin,                         // 1 canal, 0/255
        std::vector<cv::Point>& bestContour    // contorno principal
) {
    cv::Mat gray;
    cv::cvtColor(rgba, gray, cv::COLOR_RGBA2GRAY);

    // Reduce ruido (trazo fino)
    cv::GaussianBlur(gray, gray, cv::Size(3,3), 0);

    // Otsu (se adapta por imagen)
    cv::threshold(gray, bin, 0, 255, cv::THRESH_BINARY | cv::THRESH_OTSU);

    // Queremos: fondo negro, trazo blanco
    double meanVal = cv::mean(bin)[0];
    if (meanVal > 127.0) {
        cv::bitwise_not(bin, bin);
    }

    // Morfología ligera para no perder trazos finos
    cv::Mat k = cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(3,3));
    cv::morphologyEx(bin, bin, cv::MORPH_CLOSE, k, cv::Point(-1,-1), 1);

    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(bin, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_NONE);
    if (contours.empty()) return false;

    int best = 0;
    double bestArea = 0.0;
    for (int i = 0; i < (int)contours.size(); i++) {
        double a = std::abs(cv::contourArea(contours[i]));
        if (a > bestArea) { bestArea = a; best = i; }
    }

    bestContour = contours[best];
    return !bestContour.empty();
}

extern "C" {

// ✅ Status
JNIEXPORT jstring JNICALL
Java_com_example_shapesignatureapp_MainActivity_nativeHello(
        JNIEnv* env, jobject) {
    std::string msg = "JNI + OpenCV OK";
    return env->NewStringUTF(msg.c_str());
}

// ✅ BINARIZACIÓN (para mostrar en UI) -> devuelve ARGB
JNIEXPORT jintArray JNICALL
Java_com_example_shapesignatureapp_MainActivity_nativeBinarize(
        JNIEnv* env, jobject,
        jintArray argbPixels, jint width, jint height) {

    const int w = (int)width;
    const int h = (int)height;

    cv::Mat rgba = argbToRgbaMat(env, argbPixels, w, h);

    cv::Mat bin;
    std::vector<cv::Point> dummy;
    bool ok = buildBinaryAndBestContour(rgba, bin, dummy);

    cv::Mat out(h, w, CV_8UC4, cv::Scalar(0,0,0,255));
    if (ok) {
        for (int y = 0; y < h; y++) {
            const uint8_t* row = bin.ptr<uint8_t>(y);
            for (int x = 0; x < w; x++) {
                uint8_t v = row[x];
                int idx = (y*w + x)*4;
                out.data[idx+0] = v;
                out.data[idx+1] = v;
                out.data[idx+2] = v;
                out.data[idx+3] = 255;
            }
        }
    }

    return rgbaMatToArgb(env, out);
}

// ✅ Contorno dibujado (evidencia)
JNIEXPORT jintArray JNICALL
Java_com_example_shapesignatureapp_MainActivity_nativeFindContour(
        JNIEnv* env, jobject,
        jintArray argbPixels, jint width, jint height) {

    const int w = (int)width;
    const int h = (int)height;

    cv::Mat rgba = argbToRgbaMat(env, argbPixels, w, h);

    cv::Mat bin;
    std::vector<cv::Point> contour;
    bool ok = buildBinaryAndBestContour(rgba, bin, contour);

    cv::Mat out(h, w, CV_8UC4, cv::Scalar(0, 0, 0, 255));
    if (ok) {
        std::vector<std::vector<cv::Point>> tmp;
        tmp.push_back(contour);
        cv::drawContours(out, tmp, 0, cv::Scalar(0, 255, 0, 255), 2);
    }

    return rgbaMatToArgb(env, out);
}

// ✅ LITERAL B: Shape Signature por Coordenadas Complejas
// Retorna FloatArray: [Re0, Im0, Re1, Im1, ...]
JNIEXPORT jfloatArray JNICALL
Java_com_example_shapesignatureapp_MainActivity_nativeShapeSignatureComplex(
        JNIEnv* env, jobject,
        jintArray argbPixels, jint width, jint height) {

    const int w = (int)width;
    const int h = (int)height;

    cv::Mat rgba = argbToRgbaMat(env, argbPixels, w, h);

    cv::Mat bin;
    std::vector<cv::Point> contour;
    bool ok = buildBinaryAndBestContour(rgba, bin, contour);

    if (!ok) {
        return env->NewFloatArray(0);
    }

    // Centroide (momentos)
    cv::Moments m = cv::moments(contour);
    double xc = 0.0, yc = 0.0;
    if (std::abs(m.m00) > 1e-9) {
        xc = m.m10 / m.m00;
        yc = m.m01 / m.m00;
    } else {
        double sx = 0.0, sy = 0.0;
        for (const auto& p : contour) { sx += p.x; sy += p.y; }
        xc = sx / (double)contour.size();
        yc = sy / (double)contour.size();
    }

    // Señal compleja: s(n) = (x-xc) + j(y-yc)
    const int N = (int)contour.size();
    std::vector<float> sig(2 * N);

    for (int i = 0; i < N; i++) {
        sig[2*i + 0] = (float)(contour[i].x - xc); // Re
        sig[2*i + 1] = (float)(contour[i].y - yc); // Im
    }

    jfloatArray out = env->NewFloatArray((jsize)sig.size());
    env->SetFloatArrayRegion(out, 0, (jsize)sig.size(), sig.data());
    return out;
}

// ✅ LITERAL C: FFT/DFT + magnitud + normalización (por |F(1)|) + primeros K
// Entrada: signatureComplex = [Re0, Im0, Re1, Im1, ...]
// Salida: descriptor = [ |F(1)|/|F(1)|, |F(2)|/|F(1)|, ... ] (K elementos)
JNIEXPORT jfloatArray JNICALL
Java_com_example_shapesignatureapp_MainActivity_nativeFFTNormalizeFromSignature(
        JNIEnv* env, jobject,
        jfloatArray signatureComplex, jint K) {

    if (signatureComplex == nullptr) return env->NewFloatArray(0);

    jsize len = env->GetArrayLength(signatureComplex);
    if (len < 4) return env->NewFloatArray(0);

    const int N = (int)(len / 2);
    if (N < 2) return env->NewFloatArray(0);

    std::vector<float> sig((size_t)len);
    env->GetFloatArrayRegion(signatureComplex, 0, len, sig.data());

    cv::Mat input(1, N, CV_32FC2);
    for (int i = 0; i < N; i++) {
        float re = sig[2*i];
        float im = sig[2*i + 1];
        input.at<cv::Vec2f>(0, i) = cv::Vec2f(re, im);
    }

    cv::Mat F;
    cv::dft(input, F, cv::DFT_COMPLEX_OUTPUT);

    cv::Vec2f F1 = F.at<cv::Vec2f>(0, 1);
    float denom = std::sqrt(F1[0]*F1[0] + F1[1]*F1[1]);
    if (denom < 1e-9f) denom = 1e-9f;

    int kOut = (int)K;
    if (kOut <= 0) kOut = 32;

    std::vector<float> desc((size_t)kOut, 0.0f);

    for (int k = 1; k <= kOut; k++) {
        if (k < N) {
            cv::Vec2f fk = F.at<cv::Vec2f>(0, k);
            float mag = std::sqrt(fk[0]*fk[0] + fk[1]*fk[1]); // |F(k)|
            desc[(size_t)(k - 1)] = mag / denom;              // normalizado
        } else {
            desc[(size_t)(k - 1)] = 0.0f;
        }
    }

    jfloatArray out = env->NewFloatArray((jsize)desc.size());
    env->SetFloatArrayRegion(out, 0, (jsize)desc.size(), desc.data());
    return out;
}

// ✅ PARTE SIGUIENTE: distancia Euclídea entre dos descriptores (para clasificar y precisión)
// Devuelve -1 si hay error (arrays nulos o longitudes distintas)
JNIEXPORT jfloat JNICALL
Java_com_example_shapesignatureapp_MainActivity_nativeEuclideanDistance(
        JNIEnv* env, jobject,
        jfloatArray a, jfloatArray b) {

    if (a == nullptr || b == nullptr) return -1.0f;

    jsize lenA = env->GetArrayLength(a);
    jsize lenB = env->GetArrayLength(b);
    if (lenA != lenB || lenA == 0) return -1.0f;

    std::vector<float> va((size_t)lenA), vb((size_t)lenB);
    env->GetFloatArrayRegion(a, 0, lenA, va.data());
    env->GetFloatArrayRegion(b, 0, lenB, vb.data());

    float sum = 0.0f;
    for (int i = 0; i < lenA; i++) {
        float d = va[(size_t)i] - vb[(size_t)i];
        sum += d * d;
    }

    return std::sqrt(sum);
}

} // extern "C"
