"""
PaddleOCR LAN Server for EhViewer Android App
==============================================
Usage:
  python ocr_server.py                    # default port 5001, host 0.0.0.0
  python ocr_server.py --port 8080         # custom port
  python ocr_server.py --host 192.168.1.100 --port 5001

Setup:
  1. Install Python 3.9+
  2. pip install -r requirements_ocr.txt
  3. (Optional) Set OCR_MODEL_DIR env var for ONNX models
  4. Run this script

API:
  GET  /health          -> {"status":"ok","service":"ocr"}
  POST /ocr             -> {"boxes":[...],"fullText":"..."}
  GET  /model/status    -> {"loaded":bool,"model":"PaddleOCR",...}
"""

import os
import sys
import json
import base64
import argparse
import logging
from io import BytesIO

from flask import Flask, request, jsonify
from PIL import Image
import numpy as np

app = Flask(__name__)
logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(levelname)s] %(message)s')
logger = logging.getLogger(__name__)

ocr_instance = None


def get_ocr():
    global ocr_instance
    if ocr_instance is None:
        try:
            from paddleocr import PaddleOCR
        except ImportError:
            raise ImportError(
                "paddleocr not installed. Run:\n"
                "  pip install paddleocr paddlepaddle\n"
            )

        model_dir_raw = os.environ.get('OCR_MODEL_DIR', '').strip()
        model_dir = os.path.normpath(model_dir_raw) if model_dir_raw else ''

        kwargs = {
            'use_textline_orientation': True,
            'use_doc_orientation_classify': False,
            'use_doc_unwarping': False,
        }

        if model_dir and os.path.isdir(model_dir):
            logger.info("Loading PaddleOCR ONNX model from %s ...", model_dir)
            det_model = os.path.join(model_dir, 'det')
            rec_model = os.path.join(model_dir, 'rec')
            cls_model = os.path.join(model_dir, 'cls')

            kwargs['engine'] = 'onnxruntime'
            if os.path.isdir(det_model):
                kwargs['text_detection_model_dir'] = det_model
            if os.path.isdir(rec_model):
                kwargs['text_recognition_model_dir'] = rec_model
            if os.path.isdir(cls_model):
                kwargs['textline_orientation_model_dir'] = cls_model
        else:
            logger.info("Loading PaddleOCR default model (auto-download to cache)...")
            kwargs['lang'] = 'ch'

        ocr_instance = PaddleOCR(**kwargs)
        logger.info("PaddleOCR model loaded successfully")
    return ocr_instance


@app.route('/health', methods=['GET'])
def health():
    return jsonify({"status": "ok", "service": "ocr"})


@app.route('/ocr', methods=['POST'])
def ocr():
    try:
        data = request.get_json()
        image_path = data.get('image_path')
        image_base64 = data.get('image_base64')

        if image_path:
            image_path = os.path.normpath(image_path.strip())
            logger.info("OCR image path: %s", image_path)
            if not os.path.isfile(image_path):
                logger.warning("Image file not found: %s", image_path)
                return jsonify({"error": "Image file not found: " + image_path}), 404
            input_data = image_path
        elif image_base64:
            image_data = base64.b64decode(image_base64)
            image = Image.open(BytesIO(image_data))
            input_data = np.array(image)
        else:
            return jsonify({"error": "No image provided"}), 400

        ocr_engine = get_ocr()
        results = ocr_engine.predict(input_data)

        boxes = []
        full_texts = []

        for res in results:
            res_json = res.json if callable(res.json) else res.json
            if isinstance(res_json, str):
                res_json = json.loads(res_json)

            inner = res_json.get('res', res_json) if isinstance(res_json, dict) else {}
            dt_polys = inner.get('dt_polys', []) if isinstance(inner, dict) else []
            rec_texts = inner.get('rec_texts', []) if isinstance(inner, dict) else []
            rec_scores = inner.get('rec_scores', []) if isinstance(inner, dict) else []

            logger.info("OCR detected %d text region(s)", len(rec_texts))

            for i, (poly, text, score) in enumerate(zip(dt_polys, rec_texts, rec_scores)):
                poly_list = poly.tolist() if hasattr(poly, 'tolist') else list(poly)
                x = min(float(p[0]) for p in poly_list)
                y = min(float(p[1]) for p in poly_list)
                w = max(float(p[0]) for p in poly_list) - x
                h = max(float(p[1]) for p in poly_list) - y

                boxes.append({
                    "x": int(x),
                    "y": int(y),
                    "w": int(w),
                    "h": int(h),
                    "text": str(text),
                    "confidence": float(score) if score is not None else 0.0
                })
                full_texts.append(str(text))
                logger.info("  [%d] text='%s' confidence=%.4f box=(%d,%d,%d,%d)",
                            i, text, float(score or 0), int(x), int(y), int(w), int(h))

        logger.info("OCR full text: %s", ' '.join(full_texts))
        return jsonify({
            "boxes": boxes,
            "fullText": " ".join(full_texts)
        })

    except Exception as e:
        logger.error("OCR error: %s", e, exc_info=True)
        return jsonify({"error": str(e)}), 500


@app.route('/model/status', methods=['GET'])
def model_status():
    loaded = ocr_instance is not None
    model_dir = os.environ.get('OCR_MODEL_DIR', '').strip()
    return jsonify({
        "loaded": loaded,
        "model": "PaddleOCR",
        "lang": "ch",
        "model_dir": model_dir or "(default cache)",
        "format": "onnx" if model_dir else "paddle"
    })


def main():
    parser = argparse.ArgumentParser(description='PaddleOCR LAN Server for EhViewer')
    parser.add_argument('--host', default=os.environ.get('OCR_HOST', '0.0.0.0'),
                        help='Listen host (default: 0.0.0.0 for LAN access)')
    parser.add_argument('--port', type=int,
                        default=int(os.environ.get('OCR_PORT', 5001)),
                        help='Listen port (default: 5001)')
    args = parser.parse_args()

    model_dir = os.environ.get('OCR_MODEL_DIR', '').strip()
    if model_dir:
        logger.info("OCR model dir: %s", model_dir)

    logger.info("Starting OCR server on %s:%d", args.host, args.port)
    logger.info("Health check: http://%s:%d/health", args.host, args.port)
    app.run(host=args.host, port=args.port, debug=False)


if __name__ == '__main__':
    main()
