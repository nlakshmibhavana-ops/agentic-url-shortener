package io.agentflow.core;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

/**
 * Java parsing at language level 21. A fresh parser per call: StaticJavaParser keeps its configuration
 * per thread, which silently falls back to an old language level on the engine's worker threads.
 */
public final class JavaSource {

    public static class ParseError extends RuntimeException {
        public ParseError(String message) {
            super(message);
        }
    }

    private JavaSource() {
    }

    public static CompilationUnit parse(String source) {
        ParserConfiguration config = new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        ParseResult<CompilationUnit> result = new JavaParser(config).parse(source);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            String msg = result.getProblems().isEmpty() ? "parse error" : result.getProblems().getFirst().getVerboseMessage();
            throw new ParseError(msg.split("\n")[0]);
        }
        return result.getResult().get();
    }
}
