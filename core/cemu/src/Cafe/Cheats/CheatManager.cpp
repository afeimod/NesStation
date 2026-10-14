#include "Cafe/Cheats/CheatManager.h"

#include "Cafe/CafeSystem.h"
#include "Cafe/HW/MMU/MMU.h"
#include "Cafe/HW/Espresso/Recompiler/PPCRecompiler.h"
#include "Cemu/Logging/CemuLogging.h"
#include "config/ActiveSettings.h"

#include <algorithm>
#include <cctype>
#include <filesystem>
#include <fstream>
#include <sstream>

namespace Cheats
{
	CheatManager& CheatManager::GetInstance()
	{
		static CheatManager s_instance;
		return s_instance;
	}

	// Parses one cheat .txt file and appends the parsed sections to out.
	// Supported line types (community Cemu cheat format):
	//   [Name]            starts a new cheat section
	//   *comment          description/comment line (leading whitespace allowed)
	//   AAAAAAAA VVVVVVVV patch line: 32-bit virtual address + 32-bit value, hex
	// Everything else (blank lines, unknown formats) is ignored.
	static void ParseCheatFile(const std::filesystem::path& filePath, std::vector<CheatEntry>& out)
	{
		std::ifstream file(filePath, std::ios::binary);
		if (!file.is_open())
			return;

		CheatEntry current;
		auto flushCurrent = [&]()
		{
			if (!current.name.empty() && !current.patches.empty())
				out.emplace_back(std::move(current));
			current = CheatEntry{};
		};

		std::string line;
		while (std::getline(file, line))
		{
			// strip UTF-8 BOM from the very first line
			if (out.empty() && current.name.empty() && current.comment.empty() && current.patches.empty())
			{
				if (line.size() >= 3 && (uint8)line[0] == 0xEF && (uint8)line[1] == 0xBB && (uint8)line[2] == 0xBF)
					line.erase(0, 3);
			}

			// trim leading/trailing whitespace incl. CR from CRLF files
			const auto notSpace = [](unsigned char c) { return !std::isspace(c); };
			line.erase(line.begin(), std::find_if(line.begin(), line.end(), notSpace));
			line.erase(std::find_if(line.rbegin(), line.rend(), notSpace).base(), line.end());
			if (line.empty())
				continue;

			if (line.front() == '[')
			{
				const auto closePos = line.find(']');
				if (closePos == std::string::npos)
					continue;
				flushCurrent();
				current.name = line.substr(1, closePos - 1);
				continue;
			}

			if (line.front() == '*')
			{
				std::string commentLine = line.substr(1);
				if (!current.comment.empty())
					current.comment += "\n";
				current.comment += commentLine;
				continue;
			}

			// patch line: expect >= 8 hex digits for the address, optionally
			// followed by whitespace and >= 8 hex digits for the value
			auto isHex = [](unsigned char c)
			{
				return std::isdigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
			};
			std::string cleaned;
			cleaned.reserve(line.size());
			for (char c : line)
			{
				if (isHex((unsigned char)c))
					cleaned.push_back(c);
				else if (c == ' ' || c == '\t' || c == ':' || c == ',')
					cleaned.push_back(' ');
				else
				{
					cleaned.clear();
					break;
				}
			}
			if (cleaned.empty())
				continue; // unsupported line - ignore

			std::istringstream iss(cleaned);
			std::string addrPart, valuePart;
			iss >> addrPart >> valuePart;
			if (addrPart.size() < 8 || valuePart.size() < 8)
				continue;
			if (addrPart.size() > 8)
				addrPart = addrPart.substr(addrPart.size() - 8); // tolerate 0x-prefixed/32-bit-wide dumps

			CheatPatch patch;
			try
			{
				patch.address = (uint32)std::stoul(addrPart, nullptr, 16);
				patch.value = (uint32)std::stoul(valuePart.substr(0, 8), nullptr, 16);
			}
			catch (const std::exception&)
			{
				continue;
			}
			current.patches.push_back(patch);
		}
		flushCurrent();
	}

	size_t CheatManager::LoadCheatsForTitle(uint64 titleId)
	{
		std::lock_guard lock(m_mutex);
		m_cheats.clear();

		if (titleId == 0)
			return 0;

		const auto cheatsDir = ActiveSettings::GetUserDataPath("cheatCodes");
		std::error_code ec;
		if (!std::filesystem::is_directory(cheatsDir, ec))
			return 0;

		// a cheat file applies to this title when its file name contains the
		// 16-digit title id (case-insensitive)
		std::string titleIdStr = fmt::format("{:016x}", titleId);
		std::vector<CheatEntry> parsed;
		for (const auto& it : std::filesystem::directory_iterator(cheatsDir, ec))
		{
			if (ec)
				break;
			if (!it.is_regular_file())
				continue;
			auto ext = it.path().extension().string();
			std::transform(ext.begin(), ext.end(), ext.begin(), [](unsigned char c) { return std::tolower(c); });
			if (ext != ".txt")
				continue;
			auto nameLower = it.path().filename().string();
			std::transform(nameLower.begin(), nameLower.end(), nameLower.begin(), [](unsigned char c) { return std::tolower(c); });
			if (nameLower.find(titleIdStr) == std::string::npos)
				continue;
			ParseCheatFile(it.path(), parsed);
		}

		m_cheats = std::move(parsed);
		cemuLog_log(LogType::Force, "[CheatManager] loaded {} cheats for title {:016x}", m_cheats.size(), titleId);
		return m_cheats.size();
	}

	void CheatManager::UnloadCheats()
	{
		std::lock_guard lock(m_mutex);
		m_cheats.clear();
	}

	size_t CheatManager::GetCheatCount() const
	{
		std::lock_guard lock(m_mutex);
		return m_cheats.size();
	}

	std::string CheatManager::GetCheatName(size_t index) const
	{
		std::lock_guard lock(m_mutex);
		return index < m_cheats.size() ? m_cheats[index].name : std::string();
	}

	std::string CheatManager::GetCheatComment(size_t index) const
	{
		std::lock_guard lock(m_mutex);
		return index < m_cheats.size() ? m_cheats[index].comment : std::string();
	}

	bool CheatManager::IsCheatEnabled(size_t index) const
	{
		std::lock_guard lock(m_mutex);
		return index < m_cheats.size() ? m_cheats[index].enabled : false;
	}

	bool CheatManager::WritePatchValue(CheatEntry& entry, size_t patchIndex, uint32 value, bool rememberOriginal)
	{
		const CheatPatch& patch = entry.patches[patchIndex];
		if (!memory_isAddressRangeAccessible(patch.address, sizeof(uint32)))
			return false;

		if (rememberOriginal)
			entry.originalValues[patchIndex] = memory_readU32(patch.address);

		memory_writeU32(patch.address, value);
		// the patch may modify code which is already JIT-compiled
		PPCRecompiler_invalidateRange(patch.address, patch.address + sizeof(uint32));
		return true;
	}

	bool CheatManager::ApplyCheat(CheatEntry& entry)
	{
		if (entry.applied || entry.patches.empty())
			return false;

		entry.originalValues.assign(entry.patches.size(), 0);
		size_t applied = 0;
		for (size_t i = 0; i < entry.patches.size(); ++i)
		{
			if (WritePatchValue(entry, i, entry.patches[i].value, true))
				++applied;
		}
		entry.applied = applied > 0;
		return entry.applied;
	}

	bool CheatManager::UnapplyCheat(CheatEntry& entry)
	{
		if (!entry.applied)
			return false;

		for (size_t i = 0; i < entry.patches.size() && i < entry.originalValues.size(); ++i)
			WritePatchValue(entry, i, entry.originalValues[i], false);

		entry.applied = false;
		entry.originalValues.clear();
		return true;
	}

	void CheatManager::SetCheatEnabled(size_t index, bool enabled)
	{
		std::lock_guard lock(m_mutex);
		if (index >= m_cheats.size() || m_cheats[index].enabled == enabled)
			return;

		m_cheats[index].enabled = enabled;
		if (enabled)
			ApplyCheat(m_cheats[index]);
		else
			UnapplyCheat(m_cheats[index]);
	}

	void CheatManager::ReapplyAll()
	{
		std::lock_guard lock(m_mutex);
		for (auto& entry : m_cheats)
		{
			if (!entry.enabled)
				continue;
			// the restored memory reverted the patches; write them again while
			// keeping the snapshot's original values for later un-apply
			entry.applied = false;
			entry.originalValues.clear();
			ApplyCheat(entry);
		}
	}
}
