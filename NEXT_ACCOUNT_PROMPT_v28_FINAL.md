# NOVA - HANDOVER PROMPT v28. Written 2026-10-11. Paste as FIRST message with NOVA_v15_assistant.zip.
Read NEXT_ACCOUNT_PROMPT_v27_FINAL.md first (rules, workflow, repo links, Hinglish style: still valid), then HANDOFF_STATUS.md (v28 entry at the end).
## What v28 added
Task agent: the owner says a whole job once; NOVA reads the screen, asks Gemini for ONE action, checks it (AgentRules.check), runs it, loops (max 30 steps / 5 min). Files: AgentRules.kt (+AgentRulesTest.kt, tools/test_agent_contract.py), NovaAccessibilityService.agentSnapshot/agentTap/agentType, NovaService.agentBegin/agentRun (search "task agent (v28)").
## Evidence level
Written + statically checked + 6 Python contract tests + validate_skillpack pass. Kotlin NOT compiled, CI NOT run, phone NOT tested.
## Next jobs (in order)
1. Owner uploads zip -> "Unpack zip" -> "Build and Test APK". If red, fix only the red lines (likely spots: NovaService agent block, NovaAccessibilityService.collectAgent, AgentRulesTest).
2. Phone test 1: "nova kaam chatgpt kholo aur ek line likh do". Test 2: "gallery kholo aur 5th photo select karke chat gpt se edit karwao" (say the edit wish too, e.g. "background neela karo").
3. Fix only what he reports. If the agent loops/stalls: look at the history lines in agentRun (what Gemini chose) before changing prompts.
4. Ideas (only on request): a Settings switch to turn the agent off, live progress on the HUD, screenshots for the model (needs canTakeScreenshot + API 30).
Never claim DONE/WORKING without evidence. Deliver ONE zip NOVA_v15_assistant.zip (all files at root) + direct links.
