package net.filebot.cli;

import java.io.File;
import java.io.FileFilter;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import net.filebot.Language;
import net.filebot.RenameAction;
import net.filebot.format.ExpressionFileFormat;
import net.filebot.format.ExpressionFilter;
import net.filebot.format.ExpressionFormat;
import net.filebot.format.ExpressionMapper;
import net.filebot.hash.HashType;
import net.filebot.subtitle.SubtitleFormat;
import net.filebot.subtitle.SubtitleNaming;
import net.filebot.web.Datasource;
import net.filebot.web.EpisodeListProvider;
import net.filebot.web.SortOrder;

public interface CmdlineInterface {

	List<File> rename(Collection<File> files, Datasource db, String query, SortOrder order, Locale locale, ExpressionFilter filter, ExpressionMapper mapper, boolean strict, ExpressionFileFormat format, File output, RenameAction action, ConflictAction conflict, ExecCommand exec) throws Exception;

	List<File> renameLinear(List<File> files, EpisodeListProvider db, String query, SortOrder order, Locale locale, ExpressionFilter filter, ExpressionMapper mapper, ExpressionFileFormat format, File output, RenameAction action, ConflictAction conflict, ExecCommand exec) throws Exception;

	List<File> rename(Map<File, File> rename, RenameAction action, ConflictAction conflict) throws Exception;

	List<File> revert(Collection<File> files, FileFilter filter, RenameAction action) throws Exception;

	List<File> getSubtitles(Collection<File> files, String query, Language language, SubtitleFormat output, Charset encoding, SubtitleNaming format, boolean strict) throws Exception;

	List<File> getMissingSubtitles(Collection<File> files, String query, Language language, SubtitleFormat output, Charset encoding, SubtitleNaming format, boolean strict) throws Exception;

	boolean check(Collection<File> files) throws Exception;

	File compute(Collection<File> files, HashType hash, File output, Charset encoding) throws Exception;

	Stream<String> fetchEpisodeList(EpisodeListProvider db, String query, SortOrder order, Locale locale, ExpressionFilter filter, ExpressionMapper mapper, ExpressionFormat format, boolean strict) throws Exception;

	Stream<String> getMediaInfo(Collection<File> files, FileFilter filter, ExpressionFormat format) throws Exception;

	IntStream execute(Collection<File> files, FileFilter filter, ExpressionFormat format, ExecCommand exec) throws Exception;

	List<File> extract(Collection<File> files, File output, ConflictAction conflict, FileFilter filter, boolean forceExtractAll) throws Exception;

}
