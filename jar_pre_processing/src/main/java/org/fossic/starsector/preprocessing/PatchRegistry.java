package org.fossic.starsector.preprocessing;

import org.fossic.starsector.preprocessing.patches.CampaignDateWidthPatch;
import org.fossic.starsector.preprocessing.patches.CampaignEntityTooltipHighlightLayoutPatch;
import org.fossic.starsector.preprocessing.patches.BitmapFontLogicalNominalPatch;
import org.fossic.starsector.preprocessing.patches.CodexStartupProfilePatch;
import org.fossic.starsector.preprocessing.patches.CombatDeploymentFontPatch;
import org.fossic.starsector.preprocessing.patches.CodexWeaponTypeFilterPatch;
import org.fossic.starsector.preprocessing.patches.CombatMainStartupProfilePatch;
import org.fossic.starsector.preprocessing.patches.CombatHudCounterWidthPatch;
import org.fossic.starsector.preprocessing.patches.CombatPlayerStatusValueWidthPatch;
import org.fossic.starsector.preprocessing.patches.CombatTargetInfoWidthPatch;
import org.fossic.starsector.preprocessing.patches.CsvLazyErrorFormattingPatch;
import org.fossic.starsector.preprocessing.patches.CsvMergeLinearPatch;
import org.fossic.starsector.preprocessing.patches.DecodedPcmBulkReadPatch;
import org.fossic.starsector.preprocessing.patches.DecodedPcmBufferPatch;
import org.fossic.starsector.preprocessing.patches.DecodedPcmCachePatch;
import org.fossic.starsector.preprocessing.patches.FactionHostilityNoManualPatch;
import org.fossic.starsector.preprocessing.patches.FleetCardCrTextWidthPatch;
import org.fossic.starsector.preprocessing.patches.FastPngDecoderPatch;
import org.fossic.starsector.preprocessing.patches.FirstTitleFrameStartupProfilePatch;
import org.fossic.starsector.preprocessing.patches.FontDefinitionParserPatch;
import org.fossic.starsector.preprocessing.patches.FontDefinitionCursorPatch;
import org.fossic.starsector.preprocessing.patches.HeadlessConsoleLogPatch;
import org.fossic.starsector.preprocessing.patches.GlyphArrayGrowthPatch;
import org.fossic.starsector.preprocessing.patches.GlobalImeFocusPatch;
import org.fossic.starsector.preprocessing.patches.GraphicsResourceStreamSafetyPatch;
import org.fossic.starsector.preprocessing.patches.LoadingUtilsTextReadPatch;
import org.fossic.starsector.preprocessing.patches.LoadingUtilsResourceStreamSafetyPatch;
import org.fossic.starsector.preprocessing.patches.JaninoCompilationUnitDedupPatch;
import org.fossic.starsector.preprocessing.patches.JaninoBytecodeCachePatch;
import org.fossic.starsector.preprocessing.patches.JaninoSourceIndexPatch;
import org.fossic.starsector.preprocessing.patches.IntelPutFirstTagIdPatch;
import org.fossic.starsector.preprocessing.patches.NewGameSeedFieldWidthPatch;
import org.fossic.starsector.preprocessing.patches.PlanetListColumnWidthPatch;
import org.fossic.starsector.preprocessing.patches.PcmDecoderAccessPatch;
import org.fossic.starsector.preprocessing.patches.ParallelImagePreloadPatch;
import org.fossic.starsector.preprocessing.patches.ParallelSpecParsePatch;
import org.fossic.starsector.preprocessing.patches.PersistentCacheCleanupPatch;
import org.fossic.starsector.preprocessing.patches.PreloadPathDedupPatch;
import org.fossic.starsector.preprocessing.patches.PreloadResultCoordinatorPatch;
import org.fossic.starsector.preprocessing.patches.RendererHighlightColorNullPatch;
import org.fossic.starsector.preprocessing.patches.RendererDynFontPatch;
import org.fossic.starsector.preprocessing.patches.RendererHighlightRegexPatch;
import org.fossic.starsector.preprocessing.patches.ResourceLeafSynchronizationPatch;
import org.fossic.starsector.preprocessing.patches.ResourceLoaderStreamSafetyPatch;
import org.fossic.starsector.preprocessing.patches.ResourceLookupSynchronizationPatch;
import org.fossic.starsector.preprocessing.patches.ResourceStablePartitionPatch;
import org.fossic.starsector.preprocessing.patches.ResourceLoaderStartupProfilePatch;
import org.fossic.starsector.preprocessing.patches.ResourceStreamDynFontPatch;
import org.fossic.starsector.preprocessing.patches.RulesDuplicateIdPatch;
import org.fossic.starsector.preprocessing.patches.SaveDateLocalePatch;
import org.fossic.starsector.preprocessing.patches.ScriptStoreWorkerStartupProfilePatch;
import org.fossic.starsector.preprocessing.patches.ShipInfoSeparatorPatch;
import org.fossic.starsector.preprocessing.patches.SoundDecodeWorkerPatch;
import org.fossic.starsector.preprocessing.patches.StarSystemMapFontPatch;
import org.fossic.starsector.preprocessing.patches.TerrainStatusBarSeparatorPatch;
import org.fossic.starsector.preprocessing.patches.TextFieldImeHookPatch;
import org.fossic.starsector.preprocessing.patches.TopMessageHighlightLayoutPatch;
import org.fossic.starsector.preprocessing.patches.TowCableTooltipWidthPatch;
import org.fossic.starsector.preprocessing.patches.TexturePixelConversionPatch;
import org.fossic.starsector.preprocessing.patches.TextureConversionCachePatch;
import org.fossic.starsector.preprocessing.patches.TitleScreenStartupProfilePatch;
import org.fossic.starsector.preprocessing.patches.AppStateInitStartupProfilePatch;
import org.fossic.starsector.preprocessing.patches.WindowDecorationPhysicalResolutionPatch;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class PatchRegistry {
    private PatchRegistry() {
    }

    public static List<JarPatch> patches() {
        return patches(PatchSelection.defaults());
    }

    public static List<JarPatch> patches(PatchSelection selection) {
        return registrations().stream()
                .filter(registration ->
                        registration.enabledWhen().test(selection))
                .map(registration -> registration.factory().get())
                .toList();
    }

    private static List<Registration> registrations() {
        return List.of(
                register(PatchGroup.LOCALIZATION, FactionHostilityNoManualPatch::new),
                register(PatchGroup.LOCALIZATION, ShipInfoSeparatorPatch::new),
                register(PatchGroup.LOCALIZATION, CombatDeploymentFontPatch::new),
                register(PatchGroup.LOCALIZATION, CombatTargetInfoWidthPatch::new),
                register(PatchGroup.DYNFONT,
                        CombatPlayerStatusValueWidthPatch::new),
                register(PatchGroup.DYNFONT,
                        CombatHudCounterWidthPatch::new),
                register(PatchGroup.DYNFONT, FleetCardCrTextWidthPatch::new),
                register(PatchGroup.LOCALIZATION, CampaignDateWidthPatch::new),
                register(PatchGroup.LOCALIZATION, SaveDateLocalePatch::new),
                register(PatchGroup.LOCALIZATION, PlanetListColumnWidthPatch::new),
                register(PatchGroup.LOCALIZATION, StarSystemMapFontPatch::new),
                register(PatchGroup.LOCALIZATION, IntelPutFirstTagIdPatch::new),
                register(PatchGroup.LOCALIZATION,
                        WindowDecorationPhysicalResolutionPatch::new),
                register(PatchGroup.LOCALIZATION,
                        TerrainStatusBarSeparatorPatch::new),
                register(PatchGroup.LOCALIZATION,
                        TopMessageHighlightLayoutPatch::new),
                register(PatchGroup.LOCALIZATION,
                        CampaignEntityTooltipHighlightLayoutPatch::new),
                register(PatchGroup.LOCALIZATION, RendererHighlightColorNullPatch::new),
                register(PatchGroup.LOCALIZATION,
                        TowCableTooltipWidthPatch::new),
                register(PatchGroup.LOCALIZATION,
                        CodexWeaponTypeFilterPatch::new),
                register(PatchGroup.IME, GlobalImeFocusPatch::new),
                register(PatchGroup.IME, TextFieldImeHookPatch::new),
                register(PatchGroup.DYNFONT, ResourceStreamDynFontPatch::new),
                register(PatchGroup.DYNFONT, NewGameSeedFieldWidthPatch::new),
                register(PatchGroup.DYNFONT,
                        BitmapFontLogicalNominalPatch::new),
                register(PatchGroup.GUI_CONSOLE_LOG, HeadlessConsoleLogPatch::new),
                register(PatchGroup.FONT_GLYPH_COPY, GlyphArrayGrowthPatch::new),
                register(PatchGroup.FONT_LINE_PARSER, FontDefinitionParserPatch::new),
                register(PatchGroup.FONT_TOKEN_CURSOR, FontDefinitionCursorPatch::new),
                register(PatchGroup.RESOURCE_LOCKS, ResourceLeafSynchronizationPatch::new),
                register(PatchGroup.RESOURCE_LOCKS, ResourceLookupSynchronizationPatch::new),
                register(PatchGroup.RESOURCE_STREAM_SAFETY,
                        ResourceLoaderStreamSafetyPatch::new),
                register(PatchGroup.FAST_PNG, FastPngDecoderPatch::new),
                register(PatchGroup.DYNFONT,
                        RendererHighlightRegexPatch::new),
                register(PatchGroup.DYNFONT, RendererDynFontPatch::new),
                register(PatchGroup.FAST_TEXT, LoadingUtilsTextReadPatch::new),
                register(PatchGroup.CSV_ERROR_FORMATTING, CsvLazyErrorFormattingPatch::new),
                register(PatchGroup.CSV_MERGE_LINEAR, CsvMergeLinearPatch::new),
                register(PatchGroup.PARALLEL_SPEC_PARSE, ParallelSpecParsePatch::new),
                register(PatchGroup.RESOURCE_STREAM_SAFETY,
                        LoadingUtilsResourceStreamSafetyPatch::new),
                register(PatchGroup.RULES_ID_INDEX, RulesDuplicateIdPatch::new),
                register(PatchGroup.TEXTURE_PIPELINE, TexturePixelConversionPatch::new),
                register(PatchGroup.TEXTURE_CACHE, TextureConversionCachePatch::new),
                register(PatchGroup.PCM_BUFFER, DecodedPcmBufferPatch::new),
                register(PatchGroup.PCM_BULK_READ, PcmDecoderAccessPatch::new),
                register(PatchGroup.PCM_BULK_READ, DecodedPcmBulkReadPatch::new),
                register(PatchGroup.PCM_CACHE, DecodedPcmCachePatch::new),
                register(PatchGroup.SOUND_DECODE_WORKERS, SoundDecodeWorkerPatch::new),
                register(PatchGroup.RESOURCE_PARTITION, ResourceStablePartitionPatch::new),
                register(PatchGroup.PRELOAD_COORDINATION, PreloadResultCoordinatorPatch::new),
                register(PatchGroup.PRELOAD_PATH_DEDUP, PreloadPathDedupPatch::new),
                register(PatchGroup.PARALLEL_IMAGE_PRELOAD, ParallelImagePreloadPatch::new),
                register(PatchGroup.RESOURCE_STREAM_SAFETY,
                        GraphicsResourceStreamSafetyPatch::new),
                register(PatchGroup.JANINO_CU_DEDUP,
                        JaninoCompilationUnitDedupPatch::new),
                register(PatchGroup.JANINO_SOURCE_INDEX,
                        JaninoSourceIndexPatch::new),
                register(PatchGroup.JANINO_BYTECODE_CACHE,
                        JaninoBytecodeCachePatch::new),
                register(PatchGroup.PROFILING, CombatMainStartupProfilePatch::new),
                register(PatchGroup.PROFILING, ResourceLoaderStartupProfilePatch::new),
                register(PatchGroup.PROFILING, ScriptStoreWorkerStartupProfilePatch::new),
                register(PatchGroup.PROFILING, TitleScreenStartupProfilePatch::new),
                registerPersistentCacheMaintenance(
                        PersistentCacheCleanupPatch::new),
                register(PatchGroup.PROFILING, FirstTitleFrameStartupProfilePatch::new),
                register(PatchGroup.PROFILING, AppStateInitStartupProfilePatch::new),
                register(PatchGroup.PROFILING, CodexStartupProfilePatch::new));
    }

    private static Registration register(
            PatchGroup group, Supplier<JarPatch> factory) {
        return new Registration(
                selection -> selection.enabled(group), factory);
    }

    private static Registration registerPersistentCacheMaintenance(
            Supplier<JarPatch> factory) {
        return new Registration(
                selection -> selection.enabled(PatchGroup.TEXTURE_CACHE)
                        || selection.enabled(PatchGroup.PCM_CACHE)
                        || selection.enabled(
                                PatchGroup.JANINO_BYTECODE_CACHE),
                factory);
    }

    private record Registration(
            Predicate<PatchSelection> enabledWhen,
            Supplier<JarPatch> factory) {
    }
}
