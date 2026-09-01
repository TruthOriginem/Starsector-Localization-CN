package org.fossic.starsector.preprocessing;

import org.fossic.starsector.preprocessing.patches.CampaignDateWidthPatch;
import org.fossic.starsector.preprocessing.patches.CampaignEntityTooltipHighlightLayoutPatch;
import org.fossic.starsector.preprocessing.patches.BitmapFontLogicalNominalPatch;
import org.fossic.starsector.preprocessing.patches.CodexStartupProfilePatch;
import org.fossic.starsector.preprocessing.patches.CombatDeploymentFontPatch;
import org.fossic.starsector.preprocessing.patches.CombatCommandShipInfoValueWidthPatch;
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
import org.fossic.starsector.preprocessing.patches.SubmarketTitleWidthPatch;
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
import java.util.function.Supplier;

public final class PatchRegistry {
    private PatchRegistry() {
    }

    public static List<JarPatch> patches() {
        return patches(PatchSelection.defaults());
    }

    public static List<JarPatch> patches(PatchSelection selection) {
        return catalog().stream()
                .map(Supplier::get)
                .filter(patch -> selection.enabled(patch.group()))
                .toList();
    }

    private static List<Supplier<JarPatch>> catalog() {
        return List.of(
                FactionHostilityNoManualPatch::new,
                ShipInfoSeparatorPatch::new,
                CombatDeploymentFontPatch::new,
                CombatTargetInfoWidthPatch::new,
                CombatPlayerStatusValueWidthPatch::new,
                CombatCommandShipInfoValueWidthPatch::new,
                CombatHudCounterWidthPatch::new,
                FleetCardCrTextWidthPatch::new,
                SubmarketTitleWidthPatch::new,
                CampaignDateWidthPatch::new,
                SaveDateLocalePatch::new,
                PlanetListColumnWidthPatch::new,
                StarSystemMapFontPatch::new,
                IntelPutFirstTagIdPatch::new,
                WindowDecorationPhysicalResolutionPatch::new,
                TerrainStatusBarSeparatorPatch::new,
                TopMessageHighlightLayoutPatch::new,
                CampaignEntityTooltipHighlightLayoutPatch::new,
                RendererHighlightColorNullPatch::new,
                TowCableTooltipWidthPatch::new,
                CodexWeaponTypeFilterPatch::new,
                GlobalImeFocusPatch::new,
                TextFieldImeHookPatch::new,
                ResourceStreamDynFontPatch::new,
                NewGameSeedFieldWidthPatch::new,
                BitmapFontLogicalNominalPatch::new,
                HeadlessConsoleLogPatch::new,
                GlyphArrayGrowthPatch::new,
                FontDefinitionParserPatch::new,
                FontDefinitionCursorPatch::new,
                ResourceLeafSynchronizationPatch::new,
                ResourceLookupSynchronizationPatch::new,
                ResourceLoaderStreamSafetyPatch::new,
                FastPngDecoderPatch::new,
                RendererHighlightRegexPatch::new,
                RendererDynFontPatch::new,
                LoadingUtilsTextReadPatch::new,
                CsvLazyErrorFormattingPatch::new,
                CsvMergeLinearPatch::new,
                ParallelSpecParsePatch::new,
                LoadingUtilsResourceStreamSafetyPatch::new,
                RulesDuplicateIdPatch::new,
                TexturePixelConversionPatch::new,
                TextureConversionCachePatch::new,
                DecodedPcmBufferPatch::new,
                PcmDecoderAccessPatch::new,
                DecodedPcmBulkReadPatch::new,
                DecodedPcmCachePatch::new,
                SoundDecodeWorkerPatch::new,
                ResourceStablePartitionPatch::new,
                PreloadResultCoordinatorPatch::new,
                PreloadPathDedupPatch::new,
                ParallelImagePreloadPatch::new,
                GraphicsResourceStreamSafetyPatch::new,
                JaninoCompilationUnitDedupPatch::new,
                JaninoSourceIndexPatch::new,
                JaninoBytecodeCachePatch::new,
                CombatMainStartupProfilePatch::new,
                ResourceLoaderStartupProfilePatch::new,
                ScriptStoreWorkerStartupProfilePatch::new,
                TitleScreenStartupProfilePatch::new,
                PersistentCacheCleanupPatch::new,
                FirstTitleFrameStartupProfilePatch::new,
                AppStateInitStartupProfilePatch::new,
                CodexStartupProfilePatch::new);
    }
}
