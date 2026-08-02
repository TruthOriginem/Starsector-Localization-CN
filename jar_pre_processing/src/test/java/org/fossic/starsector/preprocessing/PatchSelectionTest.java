package org.fossic.starsector.preprocessing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class PatchSelectionTest {
    @Test
    void defaultsEnableRuntimeFeaturesAndOptimizationsButNotProfiling() {
        PatchSelection selection = PatchSelection.defaults();

        for (PatchGroup group : PatchGroup.values()) {
            if (group == PatchGroup.PROFILING) {
                assertFalse(selection.enabled(group), group.id());
            } else {
                assertTrue(selection.enabled(group), group.id());
            }
        }
    }

    @Test
    void missingSystemPropertyDoesNotEnableProfiling() {
        String previous = System.getProperty(
                PatchSelection.PROFILING_PROPERTY);
        try {
            System.clearProperty(PatchSelection.PROFILING_PROPERTY);

            PatchSelection selection =
                    PatchSelection.fromSystemProperties();

            assertFalse(selection.enabled(PatchGroup.PROFILING));
            assertFalse(selection.requestedProfiling());
        } finally {
            if (previous == null) {
                System.clearProperty(PatchSelection.PROFILING_PROPERTY);
            } else {
                System.setProperty(
                        PatchSelection.PROFILING_PROPERTY, previous);
            }
        }
    }

    @Test
    void noneDisablesOnlyOptimizationGroups() {
        PatchSelection selection = PatchSelection.fromOptions(
                "none", List.of(), true);

        assertTrue(selection.enabled(PatchGroup.LOCALIZATION));
        assertTrue(selection.enabled(PatchGroup.IME));
        assertTrue(selection.enabled(PatchGroup.DYNFONT));
        assertTrue(selection.enabled(PatchGroup.PROFILING));
        for (PatchGroup group : PatchGroup.optimizationGroups()) {
            assertFalse(selection.enabled(group), group.id());
        }
    }

    @Test
    void selectedOptimizationIncludesItsTransitiveDependencies() {
        PatchSelection selection = PatchSelection.fromOptions(
                "pcm-bulk-read", List.of(), false);

        assertTrue(selection.enabled(PatchGroup.PCM_BUFFER));
        assertTrue(selection.enabled(PatchGroup.PCM_BULK_READ));
        assertFalse(selection.enabled(PatchGroup.FAST_TEXT));
        assertFalse(selection.enabled(PatchGroup.PROFILING));
    }

    @Test
    void fastPngIsAnIndependentOptimizationGroup() {
        PatchSelection selection = PatchSelection.fromOptions(
                "fast-png", List.of(), false);

        assertTrue(selection.enabled(PatchGroup.FAST_PNG));
        assertFalse(selection.enabled(PatchGroup.TEXTURE_PIPELINE));
        assertFalse(selection.enabled(PatchGroup.PARALLEL_IMAGE_PRELOAD));
    }

    @Test
    void resourceStreamSafetyIsAnIndependentOptimizationGroup() {
        PatchSelection selection = PatchSelection.fromOptions(
                "resource-stream-safety", List.of(), false);

        assertTrue(selection.enabled(
                PatchGroup.RESOURCE_STREAM_SAFETY));
        assertFalse(selection.enabled(PatchGroup.FAST_PNG));
        assertFalse(selection.enabled(PatchGroup.PARALLEL_SPEC_PARSE));
        assertFalse(selection.enabled(PatchGroup.PARALLEL_IMAGE_PRELOAD));
    }

    @Test
    void textureCacheRequiresFastDecodeAndTextureConversion() {
        PatchSelection selection = PatchSelection.fromOptions(
                "texture-cache", List.of(), false);

        assertTrue(selection.enabled(PatchGroup.TEXTURE_CACHE));
        assertTrue(selection.enabled(PatchGroup.FAST_PNG));
        assertTrue(selection.enabled(PatchGroup.TEXTURE_PIPELINE));
        assertFalse(selection.enabled(PatchGroup.FAST_TEXT));

        PatchSelection dependencyDisabled = PatchSelection.fromOptions(
                "all", List.of("fast-png"), true);
        assertFalse(dependencyDisabled.enabled(PatchGroup.TEXTURE_CACHE));
    }

    @Test
    void parallelImagePreloadDependsOnResultCoordination() {
        PatchSelection selection = PatchSelection.fromOptions(
                "parallel-image-preload", List.of(), false);

        assertTrue(selection.enabled(PatchGroup.PRELOAD_COORDINATION));
        assertTrue(selection.enabled(PatchGroup.PARALLEL_IMAGE_PRELOAD));

        PatchSelection dependencyDisabled = PatchSelection.fromOptions(
                "all", List.of("preload-coordination"), true);
        assertFalse(dependencyDisabled.enabled(
                PatchGroup.PARALLEL_IMAGE_PRELOAD));
    }

    @Test
    void parallelSpecParseDependsOnlyOnResourceLocks() {
        PatchSelection selection = PatchSelection.fromOptions(
                "parallel-spec-parse", List.of(), false);

        assertTrue(selection.enabled(PatchGroup.RESOURCE_LOCKS));
        assertTrue(selection.enabled(PatchGroup.PARALLEL_SPEC_PARSE));
        assertFalse(selection.enabled(PatchGroup.FAST_TEXT));

        PatchSelection dependencyDisabled = PatchSelection.fromOptions(
                "all", List.of("resource-locks"), true);
        assertFalse(dependencyDisabled.enabled(
                PatchGroup.PARALLEL_SPEC_PARSE));
    }

    @Test
    void soundDecodeWorkersAreIndependentlySelectable() {
        PatchSelection selection = PatchSelection.fromOptions(
                "sound-decode-workers", List.of(), false);

        assertTrue(selection.enabled(
                PatchGroup.SOUND_DECODE_WORKERS));
        assertFalse(selection.enabled(PatchGroup.PCM_BUFFER));
        assertFalse(selection.enabled(
                PatchGroup.PARALLEL_IMAGE_PRELOAD));
    }

    @Test
    void preloadPathDedupDependsOnResultCoordination() {
        PatchSelection selection = PatchSelection.fromOptions(
                "preload-path-dedup", List.of(), false);

        assertTrue(selection.enabled(PatchGroup.PRELOAD_COORDINATION));
        assertTrue(selection.enabled(PatchGroup.PRELOAD_PATH_DEDUP));
        assertFalse(selection.enabled(PatchGroup.PARALLEL_IMAGE_PRELOAD));

        PatchSelection dependencyDisabled = PatchSelection.fromOptions(
                "all", List.of("preload-coordination"), true);
        assertFalse(dependencyDisabled.enabled(
                PatchGroup.PRELOAD_PATH_DEDUP));
    }

    @Test
    void guiConsoleLogIsIndependentlySelectable() {
        PatchSelection selection = PatchSelection.fromOptions(
                "gui-console-log", List.of(), false);

        assertTrue(selection.enabled(PatchGroup.GUI_CONSOLE_LOG));
        assertFalse(selection.enabled(PatchGroup.FAST_TEXT));
        assertFalse(selection.enabled(PatchGroup.TEXTURE_CACHE));
    }

    @Test
    void janinoCompilationUnitDedupIsIndependentlySelectable() {
        PatchSelection selection = PatchSelection.fromOptions(
                "janino-cu-dedup", List.of(), false);

        assertTrue(selection.enabled(PatchGroup.JANINO_CU_DEDUP));
        assertFalse(selection.enabled(PatchGroup.FAST_TEXT));
        assertFalse(selection.enabled(PatchGroup.PROFILING));
    }

    @Test
    void janinoSourceIndexDependsOnCompilationUnitDedup() {
        PatchSelection selection = PatchSelection.fromOptions(
                "janino-source-index", List.of(), false);

        assertTrue(selection.enabled(PatchGroup.JANINO_CU_DEDUP));
        assertTrue(selection.enabled(PatchGroup.JANINO_SOURCE_INDEX));
        assertFalse(selection.enabled(PatchGroup.FAST_TEXT));

        PatchSelection dependencyDisabled = PatchSelection.fromOptions(
                "all", List.of("janino-cu-dedup"), true);
        assertFalse(dependencyDisabled.enabled(
                PatchGroup.JANINO_SOURCE_INDEX));
    }

    @Test
    void janinoBytecodeCacheDependsOnTheCompleteJaninoPipeline() {
        PatchSelection selection = PatchSelection.fromOptions(
                "janino-bytecode-cache", List.of(), false);

        assertTrue(selection.enabled(PatchGroup.JANINO_CU_DEDUP));
        assertTrue(selection.enabled(PatchGroup.JANINO_SOURCE_INDEX));
        assertTrue(selection.enabled(PatchGroup.JANINO_BYTECODE_CACHE));

        PatchSelection dependencyDisabled = PatchSelection.fromOptions(
                "all", List.of("janino-source-index"), true);
        assertFalse(dependencyDisabled.enabled(
                PatchGroup.JANINO_BYTECODE_CACHE));
    }

    @Test
    void fontGlyphCopyIsIndependentlySelectable() {
        PatchSelection selection = PatchSelection.fromOptions(
                "font-glyph-copy", List.of(), false);

        assertTrue(selection.enabled(PatchGroup.FONT_GLYPH_COPY));
        assertFalse(selection.enabled(PatchGroup.GUI_CONSOLE_LOG));
        assertFalse(selection.enabled(PatchGroup.TEXTURE_PIPELINE));
    }

    @Test
    void fontLineParserIsIndependentlySelectable() {
        PatchSelection selection = PatchSelection.fromOptions(
                "font-line-parser", List.of(), false);

        assertTrue(selection.enabled(PatchGroup.FONT_LINE_PARSER));
        assertFalse(selection.enabled(PatchGroup.FONT_GLYPH_COPY));
        assertFalse(selection.enabled(PatchGroup.TEXTURE_PIPELINE));
    }

    @Test
    void fontTokenCursorDependsOnTheLineParser() {
        PatchSelection selection = PatchSelection.fromOptions(
                "font-token-cursor", List.of(), false);

        assertTrue(selection.enabled(PatchGroup.FONT_LINE_PARSER));
        assertTrue(selection.enabled(PatchGroup.FONT_TOKEN_CURSOR));
        assertFalse(selection.enabled(PatchGroup.FONT_GLYPH_COPY));

        PatchSelection dependencyDisabled = PatchSelection.fromOptions(
                "all", List.of("font-line-parser"), true);
        assertFalse(dependencyDisabled.enabled(
                PatchGroup.FONT_TOKEN_CURSOR));
    }

    @Test
    void decodedPcmCacheRequiresTheExistingPcmPipeline() {
        PatchSelection selection = PatchSelection.fromOptions(
                "pcm-cache", List.of(), false);

        assertTrue(selection.enabled(PatchGroup.PCM_BUFFER));
        assertTrue(selection.enabled(PatchGroup.PCM_BULK_READ));
        assertTrue(selection.enabled(PatchGroup.PCM_CACHE));
        assertFalse(selection.enabled(PatchGroup.FAST_TEXT));

        PatchSelection dependencyDisabled = PatchSelection.fromOptions(
                "all", List.of("pcm-buffer"), true);
        assertFalse(dependencyDisabled.enabled(PatchGroup.PCM_CACHE));
    }

    @Test
    void disablingADependencyAlsoDisablesItsDependents() {
        PatchSelection selection = PatchSelection.fromOptions(
                "all", List.of("pcm-buffer"), true);

        assertFalse(selection.enabled(PatchGroup.PCM_BUFFER));
        assertFalse(selection.enabled(PatchGroup.PCM_BULK_READ));
        assertTrue(selection.enabled(PatchGroup.FAST_TEXT));
    }

    @Test
    void anyPatchGroupCanBeExplicitlyDisabled() {
        PatchSelection selection = PatchSelection.fromOptions(
                "all", List.of("dynfont", "preload-coordination"), true);

        assertFalse(selection.enabled(PatchGroup.DYNFONT));
        assertFalse(selection.enabled(PatchGroup.PRELOAD_COORDINATION));
        assertTrue(selection.enabled(PatchGroup.IME));
    }

    @Test
    void rejectsUnknownOptimizationAndDisabledGroupNames() {
        IllegalArgumentException unknownOptimization = assertThrows(
                IllegalArgumentException.class,
                () -> PatchSelection.fromOptions(
                        "fast-text,typo", List.of(), true));
        IllegalArgumentException unknownDisabledGroup = assertThrows(
                IllegalArgumentException.class,
                () -> PatchSelection.fromOptions(
                        "all", List.of("typo"), true));

        assertTrue(unknownOptimization.getMessage().contains("typo"));
        assertTrue(unknownDisabledGroup.getMessage().contains("typo"));
    }

    @Test
    void reportsRequestedAndResolvedConfigurationInStableOrder() {
        PatchSelection selection = PatchSelection.fromOptions(
                "pcm-bulk-read,fast-text",
                List.of("profiling"),
                true);

        assertIterableEquals(
                List.of("fast-text", "pcm-buffer", "pcm-bulk-read"),
                selection.enabledOptimizationIds());
        assertIterableEquals(
                List.of("localization", "ime", "dynfont", "fast-text",
                        "pcm-buffer", "pcm-bulk-read"),
                selection.enabledGroupIds());
        assertIterableEquals(List.of("profiling"), selection.disabledGroupIds());
    }
}
