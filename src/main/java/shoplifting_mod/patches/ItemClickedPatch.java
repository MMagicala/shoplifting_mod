package shoplifting_mod.patches;

import com.evacipated.cardcrawl.modthespire.lib.*;
import com.evacipated.cardcrawl.modthespire.patcher.PatchingException;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.DamageInfo;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.shop.ShopScreen;
import com.megacrit.cardcrawl.shop.StorePotion;
import com.megacrit.cardcrawl.shop.StoreRelic;
import javassist.CannotCompileException;
import javassist.CtBehavior;
import shoplifting_mod.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class ItemClickedPatch {
    private static final float successRate = 0.5f;
    private static final int damageAmount = 0;

    // Hotkey + click listeners

    @SpirePatch(
            clz = ShopScreen.class,
            method = "update"
    )
    public static class StealCardPatch {
        @SpireInsertPatch(
                locator = ItemClickedLocator.class,
                localvars = {"hoveredCard"}
        )
        public static SpireReturn<Void> Insert(Object __instance, AbstractCard hoveredCard) {
            return CommonInsert(__instance, hoveredCard);
        }
    }

    @SpirePatch(
            clz = StorePotion.class,
            method = "update"
    )
    @SpirePatch(
            clz = StoreRelic.class,
            method = "update"
    )
    public static class StealPotionOrRelicPatch {
        @SpireInsertPatch(
                locator = ItemClickedLocator.class
        )
        public static SpireReturn<Void> Insert(Object __instance) {
            return CommonInsert(__instance, null);
        }
    }

    // Find code right after item was clicked

    private static class ItemClickedLocator extends SpireInsertLocator {
        public int[] Locate(CtBehavior ctMethodToPatch) throws CannotCompileException, PatchingException {
            Matcher matcher = new Matcher.FieldAccessMatcher(Settings.class, "isTouchScreen");
            int[] results = LineFinder.findAllInOrder(ctMethodToPatch, matcher);
            int[] desiredResults = new int[1];
            desiredResults[0] = results[results.length - 1];
            return desiredResults;
        }
    }

    /**
     * Issue punishment for getting caught or succeed in stealing the item
     * @param __instance  the item being clicked on
     * @param hoveredCard is null if the item is not a card
     */
    private static SpireReturn<Void> CommonInsert(Object __instance, AbstractCard hoveredCard) {
        int itemPrice = ShopliftingManager.getItemPrice(__instance, hoveredCard);
        if (AbstractDungeon.player.gold < itemPrice && ShopliftingMod.isConfigKeyPressed()) {
            // Attempt to steal the item
            float rollResult = ShopliftingMod.random.nextFloat();
            if (rollResult < successRate) {
                // Success! Give the player enough money and purchase the item
                AbstractDungeon.player.gold += itemPrice;
                ShopliftingManager.isItemSuccessfullyStolen = true;
                if (__instance instanceof StoreRelic) {
                    ((StoreRelic) __instance).purchaseRelic();
                } else if (__instance instanceof StorePotion) {
                    ((StorePotion) __instance).purchasePotion();
                } else if (__instance instanceof ShopScreen) {
                    try {
                        Method purchaseCardMethod = ShopScreen.class.getDeclaredMethod("purchaseCard", AbstractCard.class);
                        if (!purchaseCardMethod.isAccessible()) {
                            purchaseCardMethod.setAccessible(true);
                        }
                        // Run the patched version of the purchase method
                        purchaseCardMethod.invoke(__instance, hoveredCard);
                    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                        e.printStackTrace();
                    }
                }
            } else {
                // Take damage if caught and play sound/vfx
                AbstractDungeon.player.damage(new DamageInfo(null, damageAmount, DamageInfo.DamageType.NORMAL));
                int coin = ShopliftingMod.random.nextInt(2);
                String soundKey = coin == 1 ? "BLUNT_FAST" : "BLUNT_HEAVY";
                CardCrawlGame.sound.play(soundKey);

                // Kick player out of shop if they are alive
                if (!AbstractDungeon.player.isDead) {
                    AbstractDungeon.closeCurrentScreen();
                    ShopliftingManager.isKickedOut = true;
                }
                PunishmentManager.chooseRandomPunishment();

                // Load shopkeeper dialogue
                CutsceneManager.enqueueMerchantDialogue(DialoguePool.CAUGHT.values, 2.5f);
                CutsceneManager.enqueueMerchantDialogue(PunishmentManager.decidedPunishment.dialoguePool, 3f);
            }

            // Return early
            return SpireReturn.Return(null);
        }
        // Hotkey not pressed, return to normal
        return SpireReturn.Continue();
    }
}