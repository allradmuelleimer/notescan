# Platzhalter: Die Titelerkennung per OCR ist in der App abgeschaltet (title_detection=False).
class RapidOCR:  # noqa: D101
    def __init__(self, *args, **kwargs):
        raise RuntimeError("OCR ist in Vom Blatt nicht enthalten")
