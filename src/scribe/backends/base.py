"""Transcriber interface implemented by every backend."""

from abc import ABC, abstractmethod

import numpy as np


class Transcriber(ABC):
    @abstractmethod
    def load(self, model_name: str) -> None: ...

    @abstractmethod
    def transcribe(self, audio: np.ndarray) -> str: ...

    @property
    @abstractmethod
    def backend_label(self) -> str: ...
