package i5.las2peer.services.noracleService;

import edu.smu.tspell.wordnet.Synset;
import edu.smu.tspell.wordnet.WordNetDatabase;
import i5.las2peer.api.Context;
import i5.las2peer.api.Service;
import i5.las2peer.api.execution.*;
import i5.las2peer.api.p2p.ServiceNameVersion;
import i5.las2peer.logging.L2pLogger;
import i5.las2peer.services.noracleService.api.INoracleRecommenderService;
import i5.las2peer.services.noracleService.model.*;
import info.debatty.java.stringsimilarity.Cosine;
import org.tartarus.snowball.ext.PorterStemmer;

import java.io.*;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class NoracleRecommenderService extends Service implements INoracleRecommenderService {

    private RecommenderQuestionList getRecommendations(String agentId, VotedQuestionList votedQuestionList, int limit) throws ServiceInvocationException {

        // Normalize questions - At the moment not possible in NoracleNormalizationService because
        // no complex objects can be passed with serialization
        /*Serializable rmiResultNorm = Context.get().invoke(
        new ServiceNameVersion(NoracleNormalizationService.class.getCanonicalName(), NoracleService.API_VERSION),
        "normalizeQuestions", votedQuestionList);*/

        VotedQuestionList normVotedQuestionList = normalizeQuestions(votedQuestionList);

        // Compute Utility of questions
        /*rmiResult = Context.get().invoke(
                new ServiceNameVersion(NoracleQuestionUtilityService.class.getCanonicalName(), NoracleService.API_VERSION),
                "computeUtilityForQuestions", agentId, votedQuestionList);*/
        //HashMap<VotedQuestion, Double> recommendationUtility = computeUtilityForQuestions(agentId, votedQuestionList);
        HashMap<VotedQuestion, Double> recommendationUtility = computeUtilityForQuestions(agentId, normVotedQuestionList);

        List<VotedQuestion> topNormRecommendations = recommendationUtility
                .entrySet()
                .stream()
                .sorted(Map.Entry.<VotedQuestion, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // re-normalize
        List<VotedQuestion> topRecommendations = new VotedQuestionList();
        for (int i = 0; i < topNormRecommendations.size(); i++) {
            VotedQuestion vq = topNormRecommendations.get(i);
            votedQuestionList.stream().filter(t -> t.getQuestionId().equals(vq.getQuestionId())).findFirst().ifPresent(v -> topRecommendations.add(v));
        }

        RecommenderQuestionList recommendations = new RecommenderQuestionList();
        RecommenderQuestion rq;
        for (VotedQuestion q : topRecommendations) {

            rq = new RecommenderQuestion();
            rq.setQuestion(q);

            // set author name
            Serializable rmiResult = Context.get().invoke(
                    new ServiceNameVersion(NoracleAgentService.class.getCanonicalName(), NoracleService.API_VERSION),
                    "getAgentProfile", q.getAuthorId());
            NoracleAgentProfile ap = (NoracleAgentProfile) rmiResult;
            if (ap != null || ap.getName() != null) {
                rq.setAuthorName(ap.getName());
            }

            recommendations.add(rq);
        }

        return recommendations;
    }

    @Override
    public RecommenderQuestionList getRecommendedQuestions(String agentId) throws ServiceInvocationException {
        //logger.info("NoracleRecommenderService -> getRecommendedQuestions() with agentid " + agentId);

        // Retrieving questions
        //logger.info("Get all (voted) questions for agent with agentId = " + agentId);
        Serializable rmiResult = Context.get().invoke(
                new ServiceNameVersion(NoracleAgentService.class.getCanonicalName(), NoracleService.API_VERSION),
                "getSpaceSubscriptions", agentId);

        SpaceSubscriptionList spaces;
        if (rmiResult instanceof SpaceSubscriptionList) {
            spaces = (SpaceSubscriptionList) rmiResult;
        } else {
            spaces = new SpaceSubscriptionList();
            logger.warning("RmiResult not an instance of SpaceSubscriptionList!");
        }

        VotedQuestionList votedQuestionList = new VotedQuestionList();
        for (SpaceSubscription s : spaces) {
            rmiResult = Context.get().invoke(
                    new ServiceNameVersion(NoracleQuestionService.class.getCanonicalName(), NoracleService.API_VERSION),
                    "getAllVotedQuestions", s.getSpaceId());
            if (rmiResult instanceof VotedQuestionList) {
                votedQuestionList.addAll((VotedQuestionList) rmiResult);
            } else {
                logger.warning("RmiResult not an instance of VotedQuestionList!");
            }
        }

        RecommenderQuestionList recommenderQuestionList = getRecommendations(agentId, votedQuestionList, 9);
        return recommenderQuestionList;
    }

    @Override
    public RecommenderQuestionList getRecommendedQuestionsForSpace(String agentId, String spaceId) throws ServiceInvocationException {
        //logger.info("NoracleRecommenderService -> getRecommendedQuestionsForSpace() with agentid " + agentId + " and spaceId " + spaceId + " called");

        // Retrieving questions
        //logger.info("Get all (voted) questions of space with spaceId " + spaceId);
        Serializable rmiResult = Context.get().invoke(
                new ServiceNameVersion(NoracleQuestionService.class.getCanonicalName(), NoracleService.API_VERSION),
                "getAllVotedQuestions", spaceId);

        VotedQuestionList votedQuestionList = new VotedQuestionList();
        if (rmiResult instanceof VotedQuestionList) {
            //System.out.println("rmiResult instanceof VotedQuestionList");
            votedQuestionList = (VotedQuestionList) rmiResult;
            for(int i = 0; i < votedQuestionList.size(); i++) {
                VotedQuestion vq = votedQuestionList.get(i);
                if (vq.getVotes() != null) {
                    //System.out.println(vq.getVotes().size());
                } else {
                    //System.out.println("vq getVotes() is null");
                }
            }
        } else {
            logger.warning("RmiResult not an instance of VotedQuestionList!");
        }

        //logger.info("Found " + votedQuestionList.size() + " voted questions!");

        RecommenderQuestionList recommenderQuestionList = getRecommendations(agentId, votedQuestionList, 6);
        return recommenderQuestionList;
    }

    // #########################################################################
    // TEMP -> Utility service!
    // weights
    private final double cosineSimilarityWeight = 1;
    private final double voteSimilarityWeight = 1;
    private final double relativePositiveVoteCountWeight = 1;
    private final double relativeNegativeVoteCountWeight = -1;
    private final double positiveVoteCountWeight = 10000;
    private final double negativeVoteCountWeight = -10000;
    private final double timeFeatureWeight = 1;

    private final double sumWeights = cosineSimilarityWeight +
            voteSimilarityWeight +
            relativePositiveVoteCountWeight +
            relativeNegativeVoteCountWeight +
            positiveVoteCountWeight +
            negativeVoteCountWeight +
            timeFeatureWeight;

    private final Cosine cosine = new Cosine();

    private HashMap<VotedQuestion, Double> computeUtilityForQuestions(String agentId, VotedQuestionList questions) {
        logger.info("NoracleQuestionUtilityService -> computeUtilityForQuestions(...)");
        HashMap<VotedQuestion, Double> utilityMap = new HashMap<>();
        Date oldestDate = null;
        try {
            oldestDate = getOldestDate(questions);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        double utility;
        for (VotedQuestion q : questions) {

            //logger.info("compute utility for question: " + q.getText());
            utility = 0.0;
            boolean questionAsked = q.getAuthorId().equals(agentId);
            boolean alreadyVoted = alreadyVoted(q, agentId);
            // check if user did not create the question or gave already a vote to it
            if (!questionAsked && !alreadyVoted) {
                double cosineSimilarity  = cosineSimilarityWeight * computeMaxCosineSimilarity(agentId, q);
                double voteSimilarity = voteSimilarityWeight * computeMaxVoteSimilarity(agentId, q);
                double relativePositiveVoteCount = relativePositiveVoteCountWeight * computeRelativePositiveVoteCount(questions, q);
                double relativeNegativeVoteCount = relativeNegativeVoteCountWeight * computeRelativeNegativeVoteCount(questions, q);
                double positiveVoteCount = positiveVoteCountWeight * computeVoteCount(q, 1);
                double negativeVoteCount = negativeVoteCountWeight * computeVoteCount(q, -1);
                double timeFeature = 0.0;
                try {
                    if (oldestDate != null) {
                        timeFeature = timeFeatureWeight * computeTimeFeature(q, oldestDate);
                    }
                } catch (ParseException e) {
                    e.printStackTrace();
                }

                /*
                System.out.println(q.getText());
                System.out.println("cosineSimilarity: " + cosineSimilarity);
                System.out.println("voteSimilarity: " + voteSimilarity);
                System.out.println("relativePositiveVoteCount: " + relativePositiveVoteCount);
                System.out.println("relativeNegativeVoteCount: " + relativeNegativeVoteCount);
                System.out.println("positiveVoteCount: " + positiveVoteCount);
                System.out.println("negativeVoteCount: " + negativeVoteCount);
                System.out.println("timeFeature: " + timeFeature);
                */

                utility =
                        cosineSimilarity +
                        voteSimilarity +
                        relativePositiveVoteCount +
                        relativeNegativeVoteCount +
                        positiveVoteCount +
                        negativeVoteCount +
                        timeFeature;

                utility /= sumWeights;

                //System.out.println("total utility: " + utility);
            }

            // never recommend questions that are asked by the user itself
            if (questionAsked) {
                utility = -100.0;
            }
            //logger.info("Utility is " + utility);
            utilityMap.put(q, utility);
        }
        return utilityMap;
    }

    private Date getOldestDate(VotedQuestionList questions) {
        if (questions.size() == 0) {
            return new Date();
        }
        String timestampLastModified = questions.get(0).getTimestampLastModified();
        Date oldestDate = getDate(timestampLastModified);
        for(int i = 0; i < questions.size(); i++) {
            timestampLastModified = questions.get(i).getTimestampLastModified();
            //System.out.println(timestampLastModified);
            Date date = getDate(timestampLastModified);
            //System.out.println(date.getTime());
            oldestDate = date.getTime() < oldestDate.getTime() ? date : oldestDate;
        }
        return oldestDate;
    }

    private boolean alreadyVoted(VotedQuestion q, String agentId) {
        if (q.getVotes() != null) {
            return q.getVotes().stream().anyMatch(v -> v.getVoterAgentId().equals(agentId));
        }
        return false;
    }

    private double computeTimeFeature(VotedQuestion q, Date oldestDate) throws ParseException {
        double secondsToToday = System.currentTimeMillis() / 1000.0;
        double seconds1 = secondsToToday - (getDate(q.getTimestampLastModified()).getTime() / 1000.0);
        double seconds2 = secondsToToday - (oldestDate.getTime() / 1000.0);
        return seconds1 / seconds2;

    }

    public Date getDate (String timestampLastModified) {
        int year = Integer.parseInt(timestampLastModified.substring(0, 4));
        int month = Integer.parseInt(timestampLastModified.substring(5, 7)) - 1;
        int day = Integer.parseInt(timestampLastModified.substring(8, 10));
        int hour = Integer.parseInt(timestampLastModified.substring(11, 13));
        int minute = Integer.parseInt(timestampLastModified.substring(14, 16));
        int second = Integer.parseInt(timestampLastModified.substring(17, 19));
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.YEAR, year);
        cal.set(Calendar.MONTH, month);
        cal.set(Calendar.DAY_OF_MONTH, day);
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, second);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    private double computeVoteCount(VotedQuestion q, int value) {
        if (q.getVotes() == null) {
            return 0.0;
        }
        double ret = q.getVotes().stream().filter(v -> v.getValue() == value).collect(Collectors.toList()).size();
        ret = ret / NoracleVoteService.MAX_VOTES_PER_OBJECT;
        return ret;
    }

    private double computeMaxCosineSimilarity(String agentId, VotedQuestion question) {
        // logger.info("NoracleQuestionUtilityService -> computeMaxCosineSimilarity(...)");
        if (question == null) {
            return 0;
        }
        double maxCosineSimilarity = 0.0;
        String spaceId = question.getSpaceId();
        try {
            Serializable rmiResult = Context.get().invoke(
                    new ServiceNameVersion(NoracleQuestionService.class.getCanonicalName(), NoracleService.API_VERSION),
                    "getAllVotedQuestions", spaceId, agentId);

            VotedQuestionList userQuestions = new VotedQuestionList();
            if (rmiResult instanceof VotedQuestionList) {
                userQuestions = (VotedQuestionList) rmiResult;
            }

            for (VotedQuestion userQuestion : userQuestions) {
                double cosineSimilarity = cosine.similarity(userQuestion.getText(), question.getText());
                maxCosineSimilarity = Math.max(maxCosineSimilarity, cosineSimilarity);
            }
        } catch (Exception ex) {
            logger.warning("Exception inside computeMaxCosineSimilarity:");
            ex.printStackTrace();
        }
        // logger.info("maxCosineSimilarity: " + maxCosineSimilarity);
        return maxCosineSimilarity;
    }

    private double computeMaxVoteSimilarity(String agentId, VotedQuestion question) {
        // logger.info("NoracleQuestionUtilityService -> computeMaxVoteSimilarity(...)");
        double maxVoteSimilarity = 0.0;
        String spaceId = question.getSpaceId();
        try {
            Serializable rmiResult = Context.get().invoke(
                    new ServiceNameVersion(NoracleQuestionService.class.getCanonicalName(), NoracleService.API_VERSION),
                    "getAllVotedQuestions", spaceId, agentId);

            VotedQuestionList userQuestions = (VotedQuestionList) rmiResult;
            for (VotedQuestion userQuestion : userQuestions) {
                double voteSimilarity = computeVoteSimilarity(userQuestion, question);
                maxVoteSimilarity = Math.max(maxVoteSimilarity, voteSimilarity);
            }

        } catch (Exception ex) {
            ex.printStackTrace();
        }
        // logger.info("maxVoteSimilarity: " + maxVoteSimilarity);
        return maxVoteSimilarity;
    }

    private double computeVoteSimilarity(VotedQuestion q1, VotedQuestion q2) {
        VoteList v1 = q1.getVotes();
        VoteList v2 = q2.getVotes();
        if (v1 == null || v2 == null) {
            return 0.0;
        }

        Map<String, Integer> noOfUsers = new HashMap<>();

        for (Vote v : v1) {
            noOfUsers.put(v.getVoterAgentId(), 1);
        }

        for (Vote v : v2) {
            if (noOfUsers.containsKey(v.getVoterAgentId())) {
                noOfUsers.put(v.getVoterAgentId(), 2);
            } else {
                noOfUsers.put(v.getVoterAgentId(), 1);
            }
        }

        int noOfDistinctUsers = 0;
        for (var entry : noOfUsers.entrySet()) {
            if (entry.getValue() == 2) {
                noOfDistinctUsers++;
            }
        }

        if (noOfUsers.size() == 0) {
            return 0.0;
        } else {
            //System.out.println("noOfDistinctUsers: " + noOfDistinctUsers);
            //System.out.println("noOfUsers: " + noOfUsers.size());
            return  (double) noOfDistinctUsers / (double) noOfUsers.size();
        }
    }

    private double computeRelativePositiveVoteCount(VotedQuestionList questions, VotedQuestion q) {
        return computeRelativeVoteCount(questions, q, 1);
    }

    private double computeRelativeNegativeVoteCount(VotedQuestionList questions, VotedQuestion q) {
        return computeRelativeVoteCount(questions, q, -1);
    }

    private double computeRelativeVoteCount(VotedQuestionList questions, VotedQuestion q, int value) {
        if (q == null || q.getVotes() == null) {
            return 0.0;
        }

        int voteCount = q.getVotes().stream().filter(v -> v.getValue() == value).collect(Collectors.toList()).size();
        int voteCountAll = 0;
        for (VotedQuestion question : questions) {
            if (q.getSpaceId().equals(question.getSpaceId())) {
                voteCountAll += question.getVotes().size();
            }
        }
        double relativeVoteCount = 0.0;
        if (voteCountAll > 0) {
            relativeVoteCount = (1.0 / voteCountAll) * voteCount;
        }
        return relativeVoteCount;
    }

    // #########################################################################
    // TEMP -> Normalization service!

    public VotedQuestion normalizeQuestion(VotedQuestion question) {
        //logger.info("NoracleNormalizationService -> normalizeQuestion(...) called");
        VotedQuestion normQuestion = new VotedQuestion(question);
        String text = normQuestion.getText();

        // 0 strip
        text = text.strip();

        // 1. to lower case
        text = text.toLowerCase();

        // 2. Expanding contractions
        text = expandContractions(text);

        // 3. Removing dashes
        text = text.replaceAll("[\\s\\-()]", " ");

        // 4. Remove stop words
        text = removeStopWords(text);

        // 5. Remove question marks
        text = text.replace("?", "");

        // 6. Stemming
        text = stemming(text);

        // 7. Remove all non-letter characters
        //text = text.replaceAll("[^a-zA-Z ]", "");

        // 7. Replacing words with synonyms
        // TODO: Do actual replacing with words or index which leads to a pool of synonyms
        //text = replaceWithSynonyms(text);

        normQuestion.setText(text);

        return normQuestion;
    }

    private String replaceWithSynonyms(String text) {
        // TODO: Do actual replacing with words or index which leads to a pool of synonyms
        File f = new File("WordNet/2.1/dict");
        System.setProperty("wordnet.database.dir", f.toString());
        WordNetDatabase database = WordNetDatabase.getFileInstance();
        Synset[] synsets;
        try {
            synsets = database.getSynsets(text);
        } catch (Exception ex) {
            throw ex;
        }
        if (synsets.length > 0) {
            ArrayList<String> al = new ArrayList<String>();
            // add elements to al, including duplicates
            HashSet hs = new HashSet();
            for (int i = 0; i < synsets.length; i++) {
                String[] wordForms = synsets[i].getWordForms();
                for (int j = 0; j < wordForms.length; j++)
                {
                    al.add(wordForms[j]);
                }


                //removing duplicates
                hs.addAll(al);
                al.clear();
                al.addAll(hs);
            }
            //showing all synsets
            for (int k = 0; k < al.size(); k++) {
                //System.out.println(al.get(k));
            }
        } else {
            //System.err.println("No synsets exist that contain the word form '" + wordForm + "'");
        }
        return text;
    }


    private String removeStopWords(String inputString) {
        inputString = inputString.replace(" a ", " ");
        inputString = inputString.replace(" the ", " ");
        inputString = inputString.replace(" is ", " ");
        inputString = inputString.replace(" are ", " ");
        return inputString;
    }

    public VotedQuestionList normalizeQuestions(VotedQuestionList questionList) {
        logger.info("NoracleNormalizationService -> normalizeQuestions(...) called");
        //questionList.stream().forEach(q -> logger.info(q.getText()));
        VotedQuestionList normQuestionList = new VotedQuestionList();
        for (VotedQuestion q : questionList) {
            VotedQuestion normQ = normalizeQuestion(q);
            normQuestionList.add(normQ);
        }
        return normQuestionList;
    }

    private String expandContractions(String inputString) {
        inputString = inputString.replaceAll("n't", " not");
        inputString = inputString.replaceAll("'re", " are");
        inputString = inputString.replaceAll("'m", " am");
        inputString = inputString.replaceAll("'ll", " will");
        inputString = inputString.replaceAll("'ve", " have");
        inputString = inputString.replaceAll("'s", " is");
        return inputString;
    }

    private String stemming(String text) {
        String[] words = text.split(" ");
        String ret = "";
        for (int i = 0; i < words.length; i++) {
            if (words[i].isEmpty()) {
                continue;
            }
            if (i > 0) {
                ret += " ";
            }
            porterStemmer.setCurrent(words[i]);
            porterStemmer.stem();
            ret += porterStemmer.getCurrent();
        }
        return ret;
    }

    private static final DateFormat formatter1 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static final PorterStemmer porterStemmer = new PorterStemmer();
    private final L2pLogger logger = L2pLogger.getInstance(NoracleRecommenderService.class.getName());
}
