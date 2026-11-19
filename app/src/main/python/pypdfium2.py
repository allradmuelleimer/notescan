# Platzhalter: PDF-Import ist in der App nicht vorgesehen.
def __getattr__(name):
    raise RuntimeError("PDF wird in Vom Blatt nicht unterstützt")
