"""Erzeugt aus homrs Decoder eine Fassung für die Schritte ab 1 (Fable-Befund 18.11.2026).

homrs exportierte Schleife gibt ab Schritt 1 `context[:, :1]` hinein; die Cross-Attention-Blöcke (MultiHeadAttention ohne
`unidirectional`) hängen dessen Schlüssel/Werte jedes Mal an ihren Cache an (1280 → 1281 → …) und kopieren ihn dabei –
etwa 60 % der Schrittzeit. homrs PyTorch-Original rechnet ohne diese Kopien. Diese Fassung bekommt die Schlüssel/Werte
der Notenzeile aus Schritt 0 fest (cache_in der Cross-Blöcke als 4D-Schlüssel/Werte) und hängt nichts an; alle
Verwendungen von cache_out der Cross-Blöcke (auch für die Koordinaten-Ausgabe „attention“) lesen stattdessen cache_in.
Der Eingang `context` und die Cross-Ausgänge entfallen.

Aufruf: python tools/make_decoder_step.py <decoder.onnx> <ausgabe.onnx> [--bias]
"""
import sys

import onnx
from onnx import helper


def main(src, dst):
    m = onnx.load(src)
    g = m.graph
    cross_out_to_in = {}
    for n in g.node:
        if n.op_type != "MultiHeadAttention":
            continue
        attrs = {a.name: helper.get_attribute_value(a) for a in n.attribute}
        if attrs.get("unidirectional", 0):
            continue  # Selbst-Aufmerksamkeit: Cache wächst wie bisher
        past_k, past_v = n.input[6], n.input[7]
        pres_k, pres_v = n.output[1], n.output[2]
        cross_out_to_in[pres_k] = past_k
        cross_out_to_in[pres_v] = past_v
        # Schlüssel/Werte direkt (BNSH), kein past/present; mit --bias zusätzlich „attention_bias“: homrs Schleife hatte
        # den ersten Kontext-Schlüssel nach s Schritten (s+1)-mal im Cache – gleichwertig zu +log(s+1) auf seinem Logit
        new_inputs = [n.input[0], past_k, past_v] + (["", "", "cross_bias"] if BIAS else [])
        del n.input[:]
        n.input.extend(new_inputs)
        out0 = n.output[0]
        del n.output[:]
        n.output.extend([out0])
    # alle Leser der Cross-Ausgänge auf die festen Eingänge umlenken
    for n in g.node:
        for i, name in enumerate(n.input):
            if name in cross_out_to_in:
                n.input[i] = cross_out_to_in[name]
    if BIAS:
        g.input.append(helper.make_tensor_value_info("cross_bias", onnx.TensorProto.FLOAT, [1, 1, 1, "ctx_len"]))
    keep_out = [o for o in g.output if o.name not in cross_out_to_in]
    del g.output[:]
    g.output.extend(keep_out)
    # tote Knoten entfernen (Schlüssel/Werte aus `context`)
    needed = {o.name for o in g.output}
    alive = []
    for n in reversed(list(g.node)):
        if any(o in needed for o in n.output) or n.op_type in ("If", "Loop"):
            alive.append(n)
            needed.update(i for i in n.input if i)
            for a in n.attribute:
                if a.type == onnx.AttributeProto.GRAPH:
                    for sn in a.g.node:
                        needed.update(i for i in sn.input if i)
    alive.reverse()
    del g.node[:]
    g.node.extend(alive)
    used = {i for n in g.node for i in n.input} | {o.name for o in g.output}
    keep_in = [i for i in g.input if i.name in used]
    del g.input[:]
    g.input.extend(keep_in)
    keep_init = [t for t in g.initializer if t.name in used]
    del g.initializer[:]
    g.initializer.extend(keep_init)
    onnx.checker.check_model(m)
    onnx.save(m, dst)
    print("Eingänge:", [i.name for i in g.input][:8], "…", len(g.input))
    print("Ausgänge:", len(g.output), "Knoten:", len(g.node), "Cross-Caches fest:", len(cross_out_to_in) // 2)


BIAS = "--bias" in sys.argv

if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    main(args[0], args[1])
