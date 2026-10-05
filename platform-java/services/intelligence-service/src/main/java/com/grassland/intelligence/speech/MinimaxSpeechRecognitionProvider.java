package com.grassland.intelligence.speech;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * MiniMax native multipart ASR, retaining the shared bounded client and
 * response parser.
 */
@Component
public final class MinimaxSpeechRecognitionProvider implements SpeechRecognitionProvider {

	private final OpenAiCompatibleSpeechRecognitionProvider delegate;

	public MinimaxSpeechRecognitionProvider(OpenAiCompatibleSpeechRecognitionProvider delegate) {
		this.delegate = delegate;
	}

	@Override
	public String provider() {
		return "minimax";
	}

	@Override
	public Mono<Result> transcribe(Command command) {
		return delegate.transcribe(command, true);
	}
}
