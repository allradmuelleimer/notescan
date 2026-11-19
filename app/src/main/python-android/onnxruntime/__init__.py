"""
Schlanker Ersatz für das Python-Paket onnxruntime auf Android.

Leitet alle Modellaufrufe von homr an die offizielle ONNX Runtime für Android weiter
(Kotlin-Objekt OrtBridge). Tensoren, die homr nur weiterreicht (der KV-Cache des Decoders),
bleiben dabei auf der Java-Seite und werden nicht kopiert.
"""
import numpy as np
from java import jarray, jbyte, jclass, jlong

_Bridge = jclass("com.nicedevelopments.vomblatt.recognize.OrtBridge")
_String = jclass("java.lang.String")
_Tensor = jclass("ai.onnxruntime.OnnxTensor")

_DTYPES = {"float32", "float16", "int64", "int32", "uint8", "int8", "bool", "float64"}


def get_available_providers():
    return ["CPUExecutionProvider"]


def _to_java(arr):
    arr = np.ascontiguousarray(arr)
    dtype = str(arr.dtype)
    if dtype not in _DTYPES:
        raise TypeError("Nicht unterstützter Datentyp: " + dtype)
    data = jarray(jbyte)(arr.tobytes())
    shape = jarray(jlong)([int(d) for d in arr.shape])
    return _Bridge.tensor(data, shape, dtype)


def _to_numpy(tensor):
    raw = _Bridge.bytes(tensor)
    dtype = str(_Bridge.dtype(tensor))
    shape = tuple(int(d) for d in _Bridge.shape(tensor))
    return np.frombuffer(bytes(raw), dtype=np.dtype(dtype)).reshape(shape).copy()


class OrtValue:
    def __init__(self, handle):
        self._h = handle

    @staticmethod
    def ortvalue_from_numpy(arr, device_type="cpu", device_id=0):
        return OrtValue(_to_java(arr))

    def numpy(self):
        return _to_numpy(self._h)


class SessionOptions:
    pass


class _Result:
    def __init__(self, handle):
        self._h = handle

    def outputs(self):
        return [OrtValue(self._h.get(i)) for i in range(self._h.size())]

    def close(self):
        if self._h is not None:
            self._h.close()
            self._h = None


class IOBinding:
    """
    Speicher: Java-Tensoren und Ergebnisse geben ihren nativen Speicher nur mit close() frei (nicht bei der
    Speicherbereinigung). Eigene Eingaben werden deshalb beim nächsten Binden desselben Namens geschlossen (der Lauf,
    der sie brauchte, ist dann vorbei), alles Übrige mit close()/beim Aufräumen des Objekts.
    """

    def __init__(self, session):
        self._session = session
        self._inputs = {}
        self._own = {}
        self._outputs = []
        self._result = None
        self._prev = None

    def bind_cpu_input(self, name, arr):
        old = self._own.pop(name, None)
        if old is not None:
            _Bridge.close(old)
        t = _to_java(np.asarray(arr))
        self._own[name] = t
        self._inputs[name] = t

    def close(self):
        for t in self._own.values():
            try:
                _Bridge.close(t)
            except Exception:
                pass
        self._own = {}
        for r in (self._prev, self._result):
            if r is not None:
                r.close()
        self._prev = self._result = None

    def __del__(self):
        try:
            self.close()
        except Exception:
            pass

    def bind_ortvalue_input(self, name, value):
        self._inputs[name] = value._h

    def bind_output(self, name, device_type="cpu", device_id=0, *args, **kwargs):
        if name not in self._outputs:
            self._outputs.append(name)

    def clear_binding_inputs(self):
        self._inputs = {}

    def clear_binding_outputs(self):
        self._outputs = []

    def _run(self):
        names = list(self._inputs.keys())
        values = [self._inputs[n] for n in names]
        handle = self._session._run(names, values, self._outputs or None)
        # Vorletztes Ergebnis erst jetzt freigeben: die Cache-Tensoren des letzten Laufs
        # waren Eingaben dieses Laufs und dürfen bis hierhin nicht verschwinden.
        if self._prev is not None:
            self._prev.close()
        self._prev = self._result
        self._result = _Result(handle)
        self._inputs = {}

    def get_outputs(self):
        return self._result.outputs() if self._result is not None else []


class InferenceSession:
    def __init__(self, path, sess_options=None, providers=None, provider_options=None, **kwargs):
        self._s = _Bridge.open(str(path))

    def get_providers(self):
        return ["CPUExecutionProvider"]

    def io_binding(self):
        return IOBinding(self)

    def run_with_iobinding(self, iobinding=None, run_options=None):
        iobinding._run()

    def _run(self, names, values, outputs):
        jn = jarray(_String)(names)
        jv = jarray(_Tensor)(values)
        jo = None if outputs is None else jarray(_String)(list(outputs))
        return _Bridge.run(self._s, jn, jv, jo)

    def run(self, output_names, input_feed, run_options=None):
        names = list(input_feed.keys())
        tensors = [_to_java(np.asarray(input_feed[n])) for n in names]
        res = _Result(self._run(names, tensors, output_names))
        try:
            out = [o.numpy() for o in res.outputs()]
        finally:
            res.close()
            for t in tensors:
                _Bridge.close(t)
        return out
